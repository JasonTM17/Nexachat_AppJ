package com.nexachat.message;

import com.nexachat.message.dto.PinUpdatedPayload;
import com.nexachat.message.dto.ReactionSlice;
import com.nexachat.message.dto.ReactionUpdatedPayload;
import com.nexachat.ws.EventEnvelope;
import com.nexachat.ws.WsEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * AFTER_COMMIT fan-out for the phase-10 interaction events: both go to the
 * conversation topic in the frozen envelope (plan §11.2), with NO inbox push
 * — reactions and pins don't move unread badges.
 */
@ExtendWith(MockitoExtension.class)
class InteractionBroadcastListenerTest {

    @Mock
    private SimpMessagingTemplate messaging;

    private InteractionBroadcastListener listener;

    private final UUID conversationId = UUID.randomUUID();
    private final UUID messageId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new InteractionBroadcastListener(messaging);
    }

    @Test
    void reactionUpdated_broadcastsAggregateToTopic() {
        ReactionUpdatedPayload payload = new ReactionUpdatedPayload(messageId,
                List.of(new ReactionSlice("👍", 2, List.of(UUID.randomUUID(), UUID.randomUUID()))));

        listener.onReactionUpdated(new ReactionUpdatedEvent(conversationId, payload));

        ArgumentCaptor<EventEnvelope> event = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(messaging).convertAndSend(
                eq("/topic/conversations." + conversationId), event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(WsEventType.REACTION_UPDATED);
        assertThat(event.getValue().conversationId()).isEqualTo(conversationId);
        assertThat(event.getValue().payload()).isSameAs(payload);
        assertThat(event.getValue().serverTs()).isNotNull();
    }

    @Test
    void pinUpdated_broadcastsStateToTopic() {
        PinUpdatedPayload payload = new PinUpdatedPayload(messageId, true);

        listener.onPinUpdated(new PinUpdatedEvent(conversationId, payload));

        ArgumentCaptor<EventEnvelope> event = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(messaging).convertAndSend(
                eq("/topic/conversations." + conversationId), event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(WsEventType.PIN_UPDATED);
        assertThat(event.getValue().conversationId()).isEqualTo(conversationId);
        assertThat(event.getValue().payload()).isSameAs(payload);
    }
}
