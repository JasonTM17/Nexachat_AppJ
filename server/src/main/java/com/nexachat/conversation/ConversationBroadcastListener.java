package com.nexachat.conversation;

import com.nexachat.ws.EventEnvelope;
import com.nexachat.ws.WsEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * AFTER_COMMIT fan-out for {@link ConversationUpdatedEvent} — the
 * conversation-level twin of
 * {@link com.nexachat.message.MessageBroadcastListener} (same
 * persist-before-broadcast discipline, plan §11.3.1).
 *
 * <p>Two surfaces per §11.2: the {@code /topic/conversations.{id}} broadcast
 * for members viewing the room, and a {@code /user/queue/inbox} push per
 * affected user so inbox lists refresh — the ADDED user sees the conversation
 * appear, the REMOVED/left user sees it disappear. Removed users are named in
 * {@link ConversationUpdatedEvent#extraAudienceUserIds()} because they are no
 * longer in {@code conversation_members} when this runs.
 */
@Component
public class ConversationBroadcastListener {

    private static final Logger log = LoggerFactory.getLogger(ConversationBroadcastListener.class);

    private final SimpMessagingTemplate messaging;
    private final ConversationMemberRepository memberRepository;

    public ConversationBroadcastListener(SimpMessagingTemplate messaging,
                                         ConversationMemberRepository memberRepository) {
        this.messaging = messaging;
        this.memberRepository = memberRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConversationUpdated(ConversationUpdatedEvent event) {
        EventEnvelope envelope = EventEnvelope.of(
                WsEventType.CONVERSATION_UPDATED, event.conversationId(), event.payload());
        messaging.convertAndSend("/topic/conversations." + event.conversationId(), envelope);

        Set<UUID> audience =
                new LinkedHashSet<>(memberRepository.findMemberUserIds(event.conversationId()));
        audience.addAll(event.extraAudienceUserIds());
        for (UUID userId : audience) {
            messaging.convertAndSendToUser(userId.toString(), "/queue/inbox", envelope);
        }
        log.debug("CONVERSATION_UPDATED fanned out for {} to {} users (change={})",
                event.conversationId(), audience.size(), event.payload().change());
    }
}
