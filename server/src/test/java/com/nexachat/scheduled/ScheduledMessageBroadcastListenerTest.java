package com.nexachat.scheduled;

import com.nexachat.ws.EventEnvelope;
import com.nexachat.ws.WsEventType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The author-facing FAILED event path (Wukong amendment 7): every committed
 * state change is pushed to the author's {@code /user/queue/inbox} after
 * commit, never inside the writing transaction.
 */
class ScheduledMessageBroadcastListenerTest {

    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final ScheduledMessageBroadcastListener listener =
            new ScheduledMessageBroadcastListener(messaging);

    @Test
    void listenerRunsAfterCommitOnly() throws Exception {
        Method method = ScheduledMessageBroadcastListener.class.getMethod(
                "onScheduledMessageChanged", ScheduledMessageChangedEvent.class);
        TransactionalEventListener annotation =
                method.getAnnotation(TransactionalEventListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(annotation.fallbackExecution()).isFalse();
    }

    @Test
    void aFailedChangeIsPushedToTheAuthorsInbox() {
        UUID senderId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        UUID scheduledId = UUID.randomUUID();
        UUID clientMessageId = UUID.randomUUID();
        Instant sendAt = Instant.parse("2026-12-01T02:00:00Z");

        listener.onScheduledMessageChanged(new ScheduledMessageChangedEvent(
                scheduledId, conversationId, senderId, clientMessageId,
                "FAILED", "NOT_MEMBER", sendAt, null));

        ArgumentCaptor<EventEnvelope> envelope = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(messaging).convertAndSendToUser(
                eq(senderId.toString()), eq("/queue/inbox"), envelope.capture());
        assertThat(envelope.getValue().eventType())
                .isEqualTo(WsEventType.SCHEDULED_MESSAGE_UPDATED);
        assertThat(envelope.getValue().conversationId()).isEqualTo(conversationId);
        assertThat(envelope.getValue().payload())
                .isInstanceOf(ScheduledMessageBroadcastListener.ScheduledMessageUpdatedPayload.class);
        var payload = (ScheduledMessageBroadcastListener.ScheduledMessageUpdatedPayload)
                envelope.getValue().payload();
        assertThat(payload.scheduledId()).isEqualTo(scheduledId);
        assertThat(payload.clientMessageId()).isEqualTo(clientMessageId);
        assertThat(payload.status()).isEqualTo("FAILED");
        assertThat(payload.failureCode()).isEqualTo("NOT_MEMBER");
        assertThat(payload.sendAt()).isEqualTo(sendAt);
        assertThat(payload.sentMessageId()).isNull();
    }
}
