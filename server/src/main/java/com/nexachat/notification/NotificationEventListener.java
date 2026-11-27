package com.nexachat.notification;

import com.nexachat.call.CallRegistry;
import com.nexachat.call.CallRingingEvent;
import com.nexachat.call.CallSession;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.dto.PushRecipientRow;
import com.nexachat.message.MessageCreatedEvent;
import com.nexachat.message.MessageRepository;
import com.nexachat.presence.PresenceSessionState;
import com.nexachat.user.User;
import com.nexachat.user.UserBlockRepository;
import com.nexachat.user.UserRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class NotificationEventListener {

    private final FcmPushService fcmPushService;
    private final ConversationMemberRepository memberRepository;
    private final MessageRepository messageRepository;
    private final UserBlockRepository blockRepository;
    private final PresenceSessionState presenceState;
    private final CallRegistry callRegistry;
    private final UserRepository userRepository;
    private final UserDeviceTokenRepository tokenRepository;
    private final Clock clock;

    public NotificationEventListener(FcmPushService fcmPushService,
                                     ConversationMemberRepository memberRepository,
                                     MessageRepository messageRepository,
                                     UserBlockRepository blockRepository,
                                     PresenceSessionState presenceState,
                                     CallRegistry callRegistry,
                                     UserRepository userRepository,
                                     UserDeviceTokenRepository tokenRepository,
                                     Clock clock) {
        this.fcmPushService = fcmPushService;
        this.memberRepository = memberRepository;
        this.messageRepository = messageRepository;
        this.blockRepository = blockRepository;
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
     * <p>Ordinary pushes require current membership and admission at the time
     * of creation. The dispatch-time query also evaluates mute expiry.
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
        boolean fanOutPrecomputed = event.admittedUserIds() != null;
        // V38: a peer-suppressed send is never delivered — the only member it
        // could notify is its own sender, whom the loop below always skips.
        boolean peerSuppressed = fanOutPrecomputed
                ? event.peerSuppressedSenderId() != null
                : messageRepository.findPeerSuppressedSenderId(event.message().id()).isPresent();
        if (peerSuppressed) {
            return;
        }
        UUID conversationId = event.conversationId();
        UUID senderId = event.message().senderId();
        UUID subjectUserId = event.subjectUserId();
        Set<UUID> admittedAtCreation = fanOutPrecomputed
                ? Set.copyOf(event.admittedUserIds()) : null;
        Map<UUID, Boolean> recipients = new LinkedHashMap<>();
        for (PushRecipientRow member : memberRepository.findPushRecipients(
                conversationId, event.message().createdAt(), clock.instant())) {
            if (admittedAtCreation == null || admittedAtCreation.contains(member.userId())) {
                recipients.put(member.userId(), member.muted());
            }
        }
        // The subject still receives their own group system notice.
        if (subjectUserId != null) {
            recipients.put(subjectUserId, false);
        }
        // V38: a member who blocked the sender never gets a push for their
        // messages — in shared groups the message stays visible in history
        // but the blocker's devices stay silent.
        recipients.keySet().removeAll(new HashSet<>(
                blockRepository.findBlockerIdsBlocking(senderId, recipients.keySet())));
        Set<UUID> mentionedMemberIds = new HashSet<>();
        event.message().mentions().forEach(mention -> mentionedMemberIds.add(mention.userId()));
        boolean mentionAll = event.message().mentionAll();

        // One physical device can hold a token row per account; it must receive
        // the message once, addressed to the account signed in there.
        Map<UUID, List<UserDeviceToken>> offlineRows = new LinkedHashMap<>();
        Set<UUID> mentionedRecipients = new HashSet<>();
        for (var recipient : recipients.entrySet()) {
            UUID memberId = recipient.getKey();
            if (memberId.equals(senderId)) {
                continue;
            }
            boolean mentioned = mentionAll || mentionedMemberIds.contains(memberId);
            if (recipient.getValue() && !mentioned) {
                continue;
            }
            if (!presenceState.isOnline(memberId)) {
                offlineRows.put(memberId, new ArrayList<>());
                if (mentioned) {
                    mentionedRecipients.add(memberId);
                }
            }
        }
        // One batched fetch instead of a token query per offline member.
        if (!offlineRows.isEmpty()) {
            tokenRepository.findByUserIdIn(offlineRows.keySet()).forEach(row -> {
                List<UserDeviceToken> rows = offlineRows.get(row.getUser().getId());
                if (rows != null) {
                    rows.add(row);
                }
            });
        }

        DeviceTokenOwners.byCurrentOwner(offlineRows).forEach((recipientId, tokens) ->
                fcmPushService.sendNewMessageNotification(recipientId, tokens, conversationId,
                        event.message().id(), event.message().seq(),
                        event.message().senderDisplayName(), mentionedRecipients.contains(recipientId)));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCallRinging(CallRingingEvent event) {
        // W2A-01: a call always rings on every device — presence never gates
        // it (a socket can look online while half-open), so the invite is
        // sent unconditionally; the client self-drops a duplicate.
        callRegistry.find(event.callId()).ifPresent(session -> {
            UUID calleeId = session.calleeId();
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
                    session.mediaType().name(),
                    timestamp
            );
        });
    }
}
