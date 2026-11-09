package com.nexachat.notification;

import com.nexachat.call.CallRegistry;
import com.nexachat.call.CallRingingEvent;
import com.nexachat.call.CallSession;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.message.Message;
import com.nexachat.message.MessageCreatedEvent;
import com.nexachat.presence.PresenceSessionState;
import com.nexachat.user.User;
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
     * <p>A member who muted the conversation is skipped for ordinary messages.
     * {@code SYSTEM} messages (group membership events) keep their current
     * behavior so a member is never silently added or removed, and
     * {@link #onCallRinging} is never gated — a call must ring.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMessageCreated(MessageCreatedEvent event) {
        UUID conversationId = event.conversationId();
        UUID senderId = event.message().senderId();
        List<UUID> memberIds = memberRepository.findMemberUserIds(conversationId);
        Set<UUID> mutedMemberIds = new HashSet<>(
                memberRepository.findMutedMemberIds(conversationId, clock.instant()));
        Set<UUID> mentionedMemberIds = new HashSet<>();
        event.message().mentions().forEach(mention -> mentionedMemberIds.add(mention.userId()));
        boolean mentionAll = event.message().mentionAll();
        boolean systemNotice = Message.Type.SYSTEM.name().equals(event.message().type());

        // One physical device can hold a token row per account; collect the
        // offline recipients' tokens and de-duplicate by token value so a
        // shared device receives the message exactly once.
        Map<String, UserDeviceToken> tokensByValue = new LinkedHashMap<>();
        Set<String> mentionedTokenValues = new HashSet<>();
        for (UUID memberId : memberIds) {
            if (memberId.equals(senderId)) {
                continue;
            }
            boolean mentioned = mentionAll || mentionedMemberIds.contains(memberId);
            if (!systemNotice && mutedMemberIds.contains(memberId) && !mentioned) {
                continue;
            }
            if (!presenceState.isOnline(memberId)) {
                for (UserDeviceToken token : tokenRepository.findByUserId(memberId)) {
                    tokensByValue.putIfAbsent(token.getToken(), token);
                    if (mentioned) {
                        mentionedTokenValues.add(token.getToken());
                    }
                }
            }
        }

        List<UserDeviceToken> mentionedTokens = new ArrayList<>();
        List<UserDeviceToken> ordinaryTokens = new ArrayList<>();
        tokensByValue.forEach((tokenValue, token) -> {
            if (mentionedTokenValues.contains(tokenValue)) {
                mentionedTokens.add(token);
            } else {
                ordinaryTokens.add(token);
            }
        });
        if (!ordinaryTokens.isEmpty()) {
            fcmPushService.sendNewMessageNotification(ordinaryTokens, conversationId,
                    event.message().id(), event.message().seq(), event.message().senderDisplayName(), false);
        }
        if (!mentionedTokens.isEmpty()) {
            fcmPushService.sendNewMessageNotification(mentionedTokens, conversationId,
                    event.message().id(), event.message().seq(), event.message().senderDisplayName(), true);
        }
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
