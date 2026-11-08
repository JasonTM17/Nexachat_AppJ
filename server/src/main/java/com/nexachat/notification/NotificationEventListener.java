package com.nexachat.notification;

import com.nexachat.call.CallRegistry;
import com.nexachat.call.CallRingingEvent;
import com.nexachat.call.CallSession;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.message.MessageCreatedEvent;
import com.nexachat.presence.PresenceSessionState;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.UUID;

@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final FcmPushService fcmPushService;
    private final ConversationMemberRepository memberRepository;
    private final PresenceSessionState presenceState;
    private final CallRegistry callRegistry;
    private final UserRepository userRepository;

    public NotificationEventListener(FcmPushService fcmPushService,
                                     ConversationMemberRepository memberRepository,
                                     PresenceSessionState presenceState,
                                     CallRegistry callRegistry,
                                     UserRepository userRepository) {
        this.fcmPushService = fcmPushService;
        this.memberRepository = memberRepository;
        this.presenceState = presenceState;
        this.callRegistry = callRegistry;
        this.userRepository = userRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMessageCreated(MessageCreatedEvent event) {
        UUID conversationId = event.conversationId();
        UUID senderId = event.message().senderId();
        List<UUID> memberIds = memberRepository.findMemberUserIds(conversationId);

        for (UUID memberId : memberIds) {
            if (memberId.equals(senderId)) {
                continue;
            }
            if (!presenceState.isOnline(memberId)) {
                fcmPushService.sendNewMessageNotification(
                        memberId,
                        conversationId,
                        event.message().id(),
                        event.message().seq(),
                        event.message().senderDisplayName(),
                        event.message().content()
                );
            }
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
