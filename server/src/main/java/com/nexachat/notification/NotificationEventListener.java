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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    public NotificationEventListener(FcmPushService fcmPushService,
                                     ConversationMemberRepository memberRepository,
                                     PresenceSessionState presenceState,
                                     CallRegistry callRegistry,
                                     UserRepository userRepository,
                                     UserDeviceTokenRepository tokenRepository) {
        this.fcmPushService = fcmPushService;
        this.memberRepository = memberRepository;
        this.presenceState = presenceState;
        this.callRegistry = callRegistry;
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMessageCreated(MessageCreatedEvent event) {
        UUID conversationId = event.conversationId();
        UUID senderId = event.message().senderId();
        List<UUID> memberIds = memberRepository.findMemberUserIds(conversationId);

        // One physical device can hold a token row per account; collect the
        // offline recipients' tokens and de-duplicate by token value so a
        // shared device receives the message exactly once.
        List<UserDeviceToken> tokens = new ArrayList<>();
        Set<String> dispatched = new HashSet<>();
        for (UUID memberId : memberIds) {
            if (memberId.equals(senderId)) {
                continue;
            }
            if (!presenceState.isOnline(memberId)) {
                for (UserDeviceToken token : tokenRepository.findByUserId(memberId)) {
                    if (dispatched.add(token.getToken())) {
                        tokens.add(token);
                    }
                }
            }
        }

        if (!tokens.isEmpty()) {
            fcmPushService.sendNewMessageNotification(
                    tokens,
                    conversationId,
                    event.message().id(),
                    event.message().seq(),
                    event.message().senderDisplayName()
            );
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
