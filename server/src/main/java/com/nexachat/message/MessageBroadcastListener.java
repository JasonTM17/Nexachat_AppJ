package com.nexachat.message;

import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.message.dto.ReadReceiptPayload;
import com.nexachat.message.dto.DeliveryReceiptPayload;
import com.nexachat.message.dto.MessageDeletedPayload;
import com.nexachat.user.UserRepository;
import com.nexachat.ws.EventEnvelope;
import com.nexachat.ws.WsEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * AFTER_COMMIT fan-out (plan §11.3.1 — persist-before-broadcast): the broker
 * only ever delivers frames for committed rows, so a reconnecting client can
 * resync purely via REST without a special recovery channel.
 *
 * <p>Runs without a transaction by design — every statement here is a fresh
 * read or a broker send, never a continuation of the just-committed tx.
 */
@Component
public class MessageBroadcastListener {

    private static final Logger log = LoggerFactory.getLogger(MessageBroadcastListener.class);

    private final SimpMessagingTemplate messaging;
    private final ConversationMemberRepository memberRepository;
    private final UserRepository userRepository;

    public MessageBroadcastListener(SimpMessagingTemplate messaging,
                                    ConversationMemberRepository memberRepository,
                                    UserRepository userRepository) {
        this.messaging = messaging;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
    }

    /**
     * Topic broadcast + per-member inbox push (plan §11.2): members that are
     * not subscribed to the conversation topic still get
     * {@code /user/queue/inbox} so their unread badge updates.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageCreated(MessageCreatedEvent event) {
        EventEnvelope envelope = EventEnvelope.of(
                WsEventType.MESSAGE_CREATED, event.conversationId(), event.message());
        // The topic frame is filtered per subscriber by creation time (see the outbound membership
        // interceptor), and the inbox push below goes only to members admitted when the message was
        // created, so a replayed send never announces an old message to someone admitted later.
        messaging.convertAndSend(topicDestination(event.conversationId()), envelope);
        // A quote preview reaches only members admitted when the quoted message was sent; the
        // others get the message without it, as history shows it to them.
        var replyTo = event.message().replyTo();
        java.util.Set<UUID> admittedToQuote = replyTo == null || replyTo.createdAt() == null
                ? java.util.Set.of()
                : java.util.Set.copyOf(memberRepository.findAdmittedUserIds(
                        event.conversationId(), replyTo.createdAt()));
        EventEnvelope withoutQuote = replyTo == null ? envelope : EventEnvelope.of(
                WsEventType.MESSAGE_CREATED, event.conversationId(), event.message().withoutReplyPreview());
        for (UUID memberId : memberRepository.findAdmittedUserIds(
                event.conversationId(), event.message().createdAt())) {
            messaging.convertAndSendToUser(memberId.toString(), "/queue/inbox",
                    replyTo == null || admittedToQuote.contains(memberId) ? envelope : withoutQuote);
        }
    }

    /**
     * Read receipts are suppressed at the source when the reader hides seen
     * status: peers must not learn the watermark at all. Unread counts are
     * unaffected because the watermark was already advanced by the service.
     * The reciprocal direction (a hidden user must not receive peers'
     * receipts) is enforced per recipient on the outbound STOMP channel.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageRead(MessageReadEvent event) {
        if (!userRepository.findShowSeenById(event.userId()).orElse(true)) {
            return;
        }
        EventEnvelope envelope = EventEnvelope.of(
                WsEventType.MESSAGE_READ, event.conversationId(),
                new ReadReceiptPayload(event.userId(), event.upToSeq()));
        messaging.convertAndSend(topicDestination(event.conversationId()), envelope);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageDelivered(MessageDeliveredEvent event) {
        EventEnvelope envelope = EventEnvelope.of(
                WsEventType.MESSAGE_DELIVERED, event.conversationId(),
                new DeliveryReceiptPayload(event.userId(), event.upToSeq()));
        messaging.convertAndSend(topicDestination(event.conversationId()), envelope);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageUpdated(MessageUpdatedEvent event) {
        EventEnvelope envelope = EventEnvelope.of(
                WsEventType.MESSAGE_UPDATED, event.conversationId(), event.message());
        messaging.convertAndSend(topicDestination(event.conversationId()), envelope);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageDeleted(MessageDeletedEvent event) {
        EventEnvelope envelope = EventEnvelope.of(
                WsEventType.MESSAGE_DELETED, event.conversationId(), event.payload());
        messaging.convertAndSend(topicDestination(event.conversationId()), envelope);
    }

    private static String topicDestination(UUID conversationId) {
        return "/topic/conversations." + conversationId;
    }
}
