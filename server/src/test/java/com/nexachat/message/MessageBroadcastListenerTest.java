package com.nexachat.message;

import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.message.dto.MessageResponse;
import com.nexachat.ws.EventEnvelope;
import com.nexachat.ws.WsEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * AFTER_COMMIT fan-out: one topic broadcast plus per-member inbox push, in the
 * frozen envelope (plan §11.2). The topic frame is sent exactly once — the
 * broker fans it out to every current subscriber, so per-session sends would
 * deliver duplicates to each client.
 */
@ExtendWith(MockitoExtension.class)
class MessageBroadcastListenerTest {

    @Mock
    private SimpMessagingTemplate messaging;
    @Mock
    private ConversationMemberRepository memberRepository;

    private MessageBroadcastListener listener;

    private final UUID conversationId = UUID.randomUUID();
    private final UUID memberA = UUID.randomUUID();
    private final UUID memberB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new MessageBroadcastListener(messaging, memberRepository);
    }

    @Test
    void messageCreated_broadcastsToTopicAndEveryMemberInbox() {
        MessageResponse message = new MessageResponse(
                UUID.randomUUID(), conversationId, 7L, memberA, "A", "TEXT",
                "hi", UUID.randomUUID(), null, null, List.of(), List.of(),
                Instant.now(), null, null);
        when(memberRepository.findMemberUserIds(conversationId))
                .thenReturn(List.of(memberA, memberB));

        listener.onMessageCreated(new MessageCreatedEvent(conversationId, message));

        ArgumentCaptor<EventEnvelope> topicEvent = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(messaging).convertAndSend(
                eq("/topic/conversations." + conversationId), topicEvent.capture());
        assertThat(topicEvent.getValue().eventType()).isEqualTo(WsEventType.MESSAGE_CREATED);
        assertThat(topicEvent.getValue().conversationId()).isEqualTo(conversationId);
        assertThat(topicEvent.getValue().payload()).isSameAs(message);
        assertThat(topicEvent.getValue().serverTs()).isNotNull();

        ArgumentCaptor<EventEnvelope> inboxEvent = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(messaging).convertAndSendToUser(
                eq(memberA.toString()), eq("/queue/inbox"), inboxEvent.capture());
        assertThat(inboxEvent.getValue().eventType()).isEqualTo(WsEventType.MESSAGE_CREATED);
        verify(messaging).convertAndSendToUser(
                eq(memberB.toString()), eq("/queue/inbox"), eq(topicEvent.getValue()));
    }

    @Test
    void messageRead_broadcastsReceiptToTopic() {
        listener.onMessageRead(new MessageReadEvent(conversationId, memberA, 88L));

        ArgumentCaptor<EventEnvelope> event = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(messaging).convertAndSend(
                eq("/topic/conversations." + conversationId), event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(WsEventType.MESSAGE_READ);
        assertThat(event.getValue().conversationId()).isEqualTo(conversationId);
    }

    @Test
    void messageUpdatedIsBroadcastOnceWithTheFullResponse() {
        MessageResponse message = new MessageResponse(
                UUID.randomUUID(), conversationId, 8L, memberA, "A", "TEXT",
                "edited", UUID.randomUUID(), null, null, List.of(), List.of(),
                Instant.now(), Instant.now(), null);

        listener.onMessageUpdated(new MessageUpdatedEvent(conversationId, message));

        ArgumentCaptor<EventEnvelope> event = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(messaging).convertAndSend(
                eq("/topic/conversations." + conversationId), event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(WsEventType.MESSAGE_UPDATED);
        assertThat(event.getValue().payload()).isSameAs(message);
        verifyNoMoreInteractions(messaging);
    }

    @Test
    void messageDeletedIsBroadcastOnceWithOnlyIdAndTimestamp() {
        var payload = new com.nexachat.message.dto.MessageDeletedPayload(
                UUID.randomUUID(), Instant.parse("2026-11-05T12:00:00Z"));

        listener.onMessageDeleted(new MessageDeletedEvent(conversationId, payload));

        ArgumentCaptor<EventEnvelope> event = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(messaging).convertAndSend(
                eq("/topic/conversations." + conversationId), event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(WsEventType.MESSAGE_DELETED);
        assertThat(event.getValue().payload()).isSameAs(payload);
        verifyNoMoreInteractions(messaging);
    }
}
