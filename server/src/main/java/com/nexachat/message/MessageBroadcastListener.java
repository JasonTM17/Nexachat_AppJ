package com.nexachat.message;

import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.message.dto.ReadReceiptPayload;
import com.nexachat.message.dto.MessageDeletedPayload;
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

    public MessageBroadcastListener(SimpMessagingTemplate messaging,
                                    ConversationMemberRepository memberRepository) {
        this.messaging = messaging;
        this.memberRepository = memberRepository;
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
        messaging.convertAndSend(topicDestination(event.conversationId()), envelope);
        for (UUID memberId : memberRepository.findMemberUserIds(event.conversationId())) {
            messaging.convertAndSendToUser(memberId.toString(), "/queue/inbox", envelope);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageRead(MessageReadEvent event) {
        EventEnvelope envelope = EventEnvelope.of(
                WsEventType.MESSAGE_READ, event.conversationId(),
                new ReadReceiptPayload(event.userId(), event.upToSeq()));
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
