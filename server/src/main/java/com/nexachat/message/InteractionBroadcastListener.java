package com.nexachat.message;

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
 * AFTER_COMMIT fan-out for the interaction events (phase-10) — the
 * {@code REACTION_UPDATED} / {@code PIN_UPDATED} twin of
 * {@link MessageBroadcastListener} (same persist-before-broadcast
 * discipline, plan §11.3.1).
 *
 * <p>Topic-only by design: both signals matter to members WATCHING the
 * conversation, not to the inbox list (a reaction or pin changes no unread
 * badge), so no {@code /user/queue/inbox} push is emitted.
 */
@Component
public class InteractionBroadcastListener {

    private static final Logger log = LoggerFactory.getLogger(InteractionBroadcastListener.class);

    private final SimpMessagingTemplate messaging;

    public InteractionBroadcastListener(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReactionUpdated(ReactionUpdatedEvent event) {
        messaging.convertAndSend(topicDestination(event.conversationId()),
                EventEnvelope.of(WsEventType.REACTION_UPDATED,
                        event.conversationId(), event.payload()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPinUpdated(PinUpdatedEvent event) {
        messaging.convertAndSend(topicDestination(event.conversationId()),
                EventEnvelope.of(WsEventType.PIN_UPDATED,
                        event.conversationId(), event.payload()));
    }

    private static String topicDestination(UUID conversationId) {
        return "/topic/conversations." + conversationId;
    }
}
