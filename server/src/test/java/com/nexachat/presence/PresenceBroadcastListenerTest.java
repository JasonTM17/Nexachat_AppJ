package com.nexachat.presence;

import com.nexachat.ws.EventEnvelope;
import com.nexachat.ws.WsEventType;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class PresenceBroadcastListenerTest {

    @Test
    void onlineIsSentOnlyToThePrecomputedDirectPeerAudience() {
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        PresenceBroadcastListener listener = new PresenceBroadcastListener(messaging);
        UUID userId = UUID.randomUUID();
        UUID peerId = UUID.randomUUID();

        listener.onPresenceChanged(new UserPresenceChangedEvent(userId, true, null, List.of(peerId)));

        org.mockito.ArgumentCaptor<EventEnvelope> envelope =
                org.mockito.ArgumentCaptor.forClass(EventEnvelope.class);
        verify(messaging).convertAndSendToUser(eq(peerId.toString()), eq("/queue/presence"), envelope.capture());
        assertThat(envelope.getValue().eventType()).isEqualTo(WsEventType.USER_ONLINE);
        assertThat(envelope.getValue().conversationId()).isNull();
        assertThat(envelope.getValue().payload()).isEqualTo(new PresencePayload(userId, true, null));
        verifyNoMoreInteractions(messaging);
    }
}
