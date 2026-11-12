package com.nexachat.notification;

import com.nexachat.call.CallRegistry;
import com.nexachat.call.CallRingingEvent;
import com.nexachat.call.CallSession;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.message.MessageCreatedEvent;
import com.nexachat.presence.PresenceSessionState;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.time.Clock;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class NotificationEventListener {

    private final FcmPushService fcmPushService;
    private final ConversationMemberRepository memberRepository;
    private final PresenceSessionState presenceState;
    private final CallRegistry callRegistry;
    private final UserRepository userRepository;
    private final UserDeviceTokenRepository tokenRepository;
    private final Clock clock;

    public NotificationEventListener(FcmPushService fcmPushService,
                                     ConversationMemberRepository memberRepository,
                                     PresenceSessionState presenceState,
                                     CallRegistry callRegistry,
                                     UserRepository userRepository,
                                     UserDeviceTokenRepository tokenRepository,
                                     Clock clock) {
        this.fcmPushService = fcmPushService;
        this.memberRepository = memberRepository;
        this.presenceState = presenceState;
        this.callRegistry = callRegistry;
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.clock = clock;
    }

    /**
     * Dispatches the {@code NEW_MESSAGE} pointer to offline members.
     *
     * <p>A member who muted the conversation is skipped for ordinary messages
     * and for {@code SYSTEM} messages, unless the message mentions them or the
     * {@code SYSTEM} event is about that member (they were removed from the
     * group or their role changed), so nobody is removed or demoted silently.
     * A removed subject is no longer a member at fan-out time, so they are
     * added back to the recipient set for that one event.
     * {@link #onCallRinging} is never gated — a call must ring.
     *
     * <p>A replay (an idempotent send retry) still broadcasts so the sender
     * reaches SENT, but it must not push again: the stored row's first send
     * already notified these devices.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMessageCreated(MessageCreatedEvent event) {
        if (event.replay()) {
            return;
        }
        UUID conversationId = event.conversationId();
        UUID senderId = event.message().senderId();
        UUID subjectUserId = event.subjectUserId();
        List<UUID> memberIds = memberRepository.findMemberUserIds(conversationId);
        // A removed subject is already gone from the member list at fan-out
        // time; keep them as a candidate so the removal notice still reaches
        // their devices.
        Set<UUID> recipientIds = new LinkedHashSet<>(memberIds);
        if (subjectUserId != null) {
            recipientIds.add(subjectUserId);
        }
        Set<UUID> mutedMemberIds = new HashSet<>(
                memberRepository.findMutedMemberIds(conversationId, clock.instant()));
        Set<UUID> mentionedMemberIds = new HashSet<>();
        event.message().mentions().forEach(mention -> mentionedMemberIds.add(mention.userId()));
        boolean mentionAll = event.message().mentionAll();

        // One physical device can hold a token row per account; it must receive
        // the message once, addressed to the account signed in there.
        Map<UUID, List<UserDeviceToken>> offlineRows = new LinkedHashMap<>();
        Set<UUID> mentionedRecipients = new HashSet<>();
        for (UUID memberId : recipientIds) {
            if (memberId.equals(senderId)) {
                continue;
            }
            boolean mentioned = mentionAll || mentionedMemberIds.contains(memberId);
            boolean subject = memberId.equals(subjectUserId);
            if (mutedMemberIds.contains(memberId) && !mentioned && !subject) {
                continue;
            }
            if (!presenceState.isOnline(memberId)) {
                offlineRows.put(memberId, tokenRepository.findByUserId(memberId));
                if (mentioned) {
                    mentionedRecipients.add(memberId);
                }
            }
        }

        DeviceTokenOwners.byCurrentOwner(offlineRows).forEach((recipientId, tokens) ->
                fcmPushService.sendNewMessageNotification(recipientId, tokens, conversationId,
                        event.message().id(), event.message().seq(),
                        event.message().senderDisplayName(), mentionedRecipients.contains(recipientId)));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCallRinging(CallRingingEvent event) {
        callRegistry.find(event.callId()).ifPresent(session -> {
            UUID calleeId = session.calleeId();
            if (!presenceState.isOnline(calleeId)) {
                String callerName = userRepository.findById(session.callerId())
                        .map(User::getDisplayName)
                        .orElse("Unknown Caller");

                long timestamp = session.startedAt() != null
                        ? session.startedAt().toEpochMilli()
                        : System.currentTimeMillis();

                fcmPushService.sendCallInviteNotification(
                        calleeId,
                        session.callId(),
                        session.conversationId(),
                        session.callerId(),
                        callerName,
                        "AUDIO",
                        timestamp
                );
            }
        });
    }
}
