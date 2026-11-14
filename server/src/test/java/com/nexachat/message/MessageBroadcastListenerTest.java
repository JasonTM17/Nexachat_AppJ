package com.nexachat.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.message.dto.MessageResponse;
import com.nexachat.user.UserRepository;
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
import static org.mockito.ArgumentMatchers.any;
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
    @Mock
    private UserRepository userRepository;

    private MessageBroadcastListener listener;

    private final UUID conversationId = UUID.randomUUID();
    private final UUID memberA = UUID.randomUUID();
    private final UUID memberB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new MessageBroadcastListener(messaging, memberRepository, userRepository);
    }

    @Test
    void messageCreated_broadcastsToTopicAndEveryMemberInbox() {
        MessageResponse message = new MessageResponse(
                UUID.randomUUID(), conversationId, 7L, memberA, "A", "TEXT",
                "hi", UUID.randomUUID(), null, null, List.of(), List.of(),
                Instant.now(), null, null,
                List.of(new com.nexachat.message.dto.MessageMentionResponse(memberB, 0, 2)), false);
        when(memberRepository.findAdmittedUserIds(eq(conversationId), any()))
                .thenReturn(List.of(memberA, memberB));

        listener.onMessageCreated(new MessageCreatedEvent(conversationId, message));

        ArgumentCaptor<EventEnvelope> topicEvent = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(messaging).convertAndSend(
                eq("/topic/conversations." + conversationId), topicEvent.capture());
        assertThat(topicEvent.getValue().eventType()).isEqualTo(WsEventType.MESSAGE_CREATED);
        assertThat(topicEvent.getValue().conversationId()).isEqualTo(conversationId);
        assertThat(topicEvent.getValue().payload()).isSameAs(message);
        assertThat(topicEvent.getValue().serverTs()).isNotNull();
        JsonNode wire = new ObjectMapper().findAndRegisterModules().valueToTree(topicEvent.getValue());
        assertThat(wire.at("/payload/mentions").isArray()).isTrue();
        assertThat(wire.at("/payload/mentions/0/userId").asText()).isEqualTo(memberB.toString());
        assertThat(wire.at("/payload/mentions/0/offset").asInt()).isZero();
        assertThat(wire.at("/payload/mentions/0/length").asInt()).isEqualTo(2);
        assertThat(wire.at("/payload/mentionAll").asBoolean()).isFalse();

        ArgumentCaptor<EventEnvelope> inboxEvent = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(messaging).convertAndSendToUser(
                eq(memberA.toString()), eq("/queue/inbox"), inboxEvent.capture());
        assertThat(inboxEvent.getValue().eventType()).isEqualTo(WsEventType.MESSAGE_CREATED);
        verify(messaging).convertAndSendToUser(
                eq(memberB.toString()), eq("/queue/inbox"), eq(topicEvent.getValue()));
    }

    @Test
    void replayedSend_republishesToTheTopicButOnlyReachesAdmittedInboxes() {
        MessageResponse message = new MessageResponse(
                UUID.randomUUID(), conversationId, 7L, memberA, "A", "TEXT",
                "hi", UUID.randomUUID(), null, null, List.of(), List.of(),
                Instant.parse("2026-01-01T00:00:00Z"), null, null, List.of(), false);
        when(memberRepository.findAdmittedUserIds(conversationId, Instant.parse("2026-01-01T00:00:00Z")))
                .thenReturn(List.of(memberA));

        listener.onMessageCreated(new MessageCreatedEvent(conversationId, message, null, true));

        verify(messaging).convertAndSend(
                eq("/topic/conversations." + conversationId), org.mockito.ArgumentMatchers.<Object>any());
        verify(messaging).convertAndSendToUser(eq(memberA.toString()), eq("/queue/inbox"),
                org.mockito.ArgumentMatchers.<Object>any());
        verify(messaging, org.mockito.Mockito.never()).convertAndSendToUser(
                eq(memberB.toString()), eq("/queue/inbox"), org.mockito.ArgumentMatchers.<Object>any());
    }

    @Test
    void inboxPushDropsTheQuotePreviewForMembersWhoJoinedAfterTheQuotedMessage() {
        Instant quotedAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant sentAt = Instant.parse("2026-01-02T00:00:00Z");
        UUID quotedId = UUID.randomUUID();
        var quote = new com.nexachat.message.dto.ReplyRefResponse(
                quotedId, memberA, "A", "TEXT", "said before B joined", false, quotedAt);
        MessageResponse message = new MessageResponse(
                UUID.randomUUID(), conversationId, 9L, memberA, "A", "TEXT",
                "reply", UUID.randomUUID(), quotedId, quote, List.of(), List.of(),
                sentAt, null, null, List.of(), false);
        when(memberRepository.findAdmittedUserIds(conversationId, sentAt)).thenReturn(List.of(memberA, memberB));
        when(memberRepository.findAdmittedUserIds(conversationId, quotedAt)).thenReturn(List.of(memberA));

        listener.onMessageCreated(new MessageCreatedEvent(conversationId, message));

        ArgumentCaptor<EventEnvelope> toA = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(messaging).convertAndSendToUser(eq(memberA.toString()), eq("/queue/inbox"), toA.capture());
        assertThat(((MessageResponse) toA.getValue().payload()).replyTo()).isEqualTo(quote);
        ArgumentCaptor<EventEnvelope> toB = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(messaging).convertAndSendToUser(eq(memberB.toString()), eq("/queue/inbox"), toB.capture());
        MessageResponse forB = (MessageResponse) toB.getValue().payload();
        assertThat(forB.replyTo()).isNull();
        assertThat(forB.replyToId()).isEqualTo(quotedId);
        assertThat(forB.content()).isEqualTo("reply");
    }

    @Test
    void messageRead_broadcastsReceiptToTopic() {
        when(userRepository.findShowSeenById(memberA)).thenReturn(java.util.Optional.of(true));

        listener.onMessageRead(new MessageReadEvent(conversationId, memberA, 88L));

        ArgumentCaptor<EventEnvelope> event = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(messaging).convertAndSend(
                eq("/topic/conversations." + conversationId), event.capture());
        assertThat(event.getValue().eventType()).isEqualTo(WsEventType.MESSAGE_READ);
        assertThat(event.getValue().conversationId()).isEqualTo(conversationId);
    }

    @Test
    void messageRead_isNotBroadcastWhenTheReaderHidesSeen() {
        when(userRepository.findShowSeenById(memberA)).thenReturn(java.util.Optional.of(false));

        listener.onMessageRead(new MessageReadEvent(conversationId, memberA, 88L));

        verifyNoMoreInteractions(messaging);
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
