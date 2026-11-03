package com.nexachat.conversation;

import com.nexachat.conversation.dto.ConversationUpdatePayload;
import com.nexachat.conversation.dto.MemberResponse;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AFTER_COMMIT fan-out of {@code CONVERSATION_UPDATED}: topic broadcast plus
 * per-member inbox push, with explicit audience for users who left/ were
 * removed (no longer returned by the member query).
 */
@ExtendWith(MockitoExtension.class)
class ConversationBroadcastListenerTest {

    @Mock
    private SimpMessagingTemplate messaging;
    @Mock
    private ConversationMemberRepository memberRepository;

    private ConversationBroadcastListener listener;

    private final UUID conversationId = UUID.randomUUID();
    private final UUID memberA = UUID.randomUUID();
    private final UUID memberB = UUID.randomUUID();
    private final UUID kicked = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new ConversationBroadcastListener(messaging, memberRepository);
    }

    private ConversationUpdatedEvent event(List<UUID> extraAudience) {
        ConversationUpdatePayload payload = new ConversationUpdatePayload(
                conversationId, "Team", null, "MEMBER_REMOVED", List.of(
                        new MemberResponse(memberA, "A", "+84901", null,
                                ConversationMember.Role.ADMIN, Instant.now())));
        return new ConversationUpdatedEvent(conversationId, payload, extraAudience);
    }

    @Test
    void conversationUpdated_broadcastsToTopicMembersAndExtraAudience() {
        when(memberRepository.findMemberUserIds(conversationId))
                .thenReturn(List.of(memberA, memberB));

        listener.onConversationUpdated(event(List.of(kicked)));

        ArgumentCaptor<EventEnvelope> topicEvent = ArgumentCaptor.forClass(EventEnvelope.class);
        verify(messaging).convertAndSend(
                eq("/topic/conversations." + conversationId), topicEvent.capture());
        assertThat(topicEvent.getValue().eventType()).isEqualTo(WsEventType.CONVERSATION_UPDATED);
        assertThat(topicEvent.getValue().conversationId()).isEqualTo(conversationId);
        assertThat(((ConversationUpdatePayload) topicEvent.getValue().payload()).change())
                .isEqualTo("MEMBER_REMOVED");

        // Members AND the kicked user get the inbox push — kicked users need it
        // so their conversation list drops the room.
        for (UUID userId : List.of(memberA, memberB, kicked)) {
            verify(messaging).convertAndSendToUser(
                    eq(userId.toString()), eq("/queue/inbox"), eq(topicEvent.getValue()));
        }
    }

    @Test
    void conversationUpdated_extraAudienceOverlappingMember_pushesOnce() {
        when(memberRepository.findMemberUserIds(conversationId))
                .thenReturn(List.of(memberA, memberB));

        listener.onConversationUpdated(event(List.of(memberA)));

        // memberA is both a member AND extra audience → pushed exactly once.
        verify(messaging, times(2))
                .convertAndSendToUser(org.mockito.ArgumentMatchers.anyString(),
                        eq("/queue/inbox"), org.mockito.ArgumentMatchers.any());
        verify(messaging, times(1))
                .convertAndSendToUser(eq(memberA.toString()), eq("/queue/inbox"),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void conversationUpdated_noExtraAudience_membersOnly() {
        when(memberRepository.findMemberUserIds(conversationId))
                .thenReturn(List.of(memberA));

        listener.onConversationUpdated(event(List.of()));

        verify(messaging, times(1))
                .convertAndSendToUser(eq(memberA.toString()), eq("/queue/inbox"),
                        org.mockito.ArgumentMatchers.any());
        verify(messaging, never())
                .convertAndSendToUser(eq(kicked.toString()), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any());
    }
}
