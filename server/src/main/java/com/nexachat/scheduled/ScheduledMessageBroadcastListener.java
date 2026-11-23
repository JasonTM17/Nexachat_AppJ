package com.nexachat.scheduled;

import com.nexachat.ws.EventEnvelope;
import com.nexachat.ws.WsEventType;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * AFTER_COMMIT fan-out for {@link ScheduledMessageChangedEvent}: the author's
 * devices each get a {@code SCHEDULED_MESSAGE_UPDATED} frame on
 * {@code /user/queue/inbox} so every device converges its scheduled-message
 * list — most importantly when a dispatch FAILS somewhere the author is not
 * watching (Wukong amendment 7). Persist-before-broadcast, same discipline
 * as {@link com.nexachat.message.MessageBroadcastListener}.
 *
 * <p>The payload never carries the body: terminal transitions have already
 * scrubbed it, and a PENDING row's body is available through GET.</p>
 */
@Component
public class ScheduledMessageBroadcastListener {

    private static final Logger log =
            LoggerFactory.getLogger(ScheduledMessageBroadcastListener.class);

    private final SimpMessagingTemplate messaging;

    public ScheduledMessageBroadcastListener(SimpMessagingTemplate messaging) {
        this.messaging = messaging;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onScheduledMessageChanged(ScheduledMessageChangedEvent event) {
        EventEnvelope envelope = EventEnvelope.of(
                WsEventType.SCHEDULED_MESSAGE_UPDATED, event.conversationId(),
                new ScheduledMessageUpdatedPayload(
                        event.scheduledId(), event.clientMessageId(), event.status(),
                        event.failureCode(), event.sendAt(), event.sentMessageId()));
        messaging.convertAndSendToUser(event.senderId().toString(), "/queue/inbox", envelope);
        log.debug("SCHEDULED_MESSAGE_UPDATED pushed to {} for {} (status={})",
                event.senderId(), event.scheduledId(), event.status());
    }

    /**
     * Author-facing frame payload. The client keys the row off
     * {@code scheduledId}/{@code clientMessageId} and applies the status:
     * PENDING upserts the entry, SENT links it to the delivered message, and
     * CANCELED/FAILED surface the terminal state (FAILED carries
     * {@code failureCode}).
     */
    public record ScheduledMessageUpdatedPayload(
            UUID scheduledId,
            UUID clientMessageId,
            String status,
            String failureCode,
            Instant sendAt,
            UUID sentMessageId
    ) {
    }
}
