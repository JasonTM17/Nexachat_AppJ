package com.nexachat.presence;

import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.conversation.dto.InboxEntry;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PresenceServiceTest {

    @Mock
    private UserRepository userRepository;
    @Mock
    private ConversationMemberRepository memberRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final PresenceSessionState sessionState = new PresenceSessionState();
    private PresenceService presenceService;
    private final UUID subjectId = UUID.randomUUID();
    private final UUID peerId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        presenceService = new PresenceService(userRepository, memberRepository, sessionState, eventPublisher);
    }

    @Test
    void visibleSubjectFansOutOnlyToDirectPeers() {
        when(userRepository.findById(subjectId)).thenReturn(java.util.Optional.of(
                User.create("+84901112233", "hash", "Subject")));
        when(memberRepository.findDirectPeerUserIds(subjectId)).thenReturn(List.of(peerId));

        presenceService.connected(subjectId);

        verify(eventPublisher).publishEvent(new UserPresenceChangedEvent(subjectId, true, null, List.of(peerId)));
    }

    @Test
    void hiddenSubjectEmitsNoEvent() {
        User hidden = User.create("+84901112233", "hash", "Subject");
        hidden.changeShowPresence(false);
        when(userRepository.findById(subjectId)).thenReturn(java.util.Optional.of(hidden));

        presenceService.connected(subjectId);

        verify(memberRepository, never()).findDirectPeerUserIds(subjectId);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void peerSnapshotOmitsHiddenUsersAndHidesLastSeenWhileOnline() {
        Instant lastSeenAt = Instant.parse("2026-11-05T11:00:00Z");
        when(memberRepository.findDirectPeerPresence(subjectId, List.of(conversationId))).thenReturn(List.of(
                new DirectPeerPresenceRow(conversationId, peerId, true, lastSeenAt)));
        sessionState.setOnline(peerId, true);

        Map<UUID, InboxEntry.PeerPresence> result =
                presenceService.peerPresence(subjectId, List.of(conversationId));

        assertThat(result).containsEntry(conversationId, new InboxEntry.PeerPresence(true, null));
    }

    @Test
    void offlinePeerSnapshotContainsPersistedLastSeenAndHiddenPeerIsOmitted() {
        UUID hiddenConversation = UUID.randomUUID();
        Instant lastSeenAt = Instant.parse("2026-11-05T11:00:00Z");
        when(memberRepository.findDirectPeerPresence(subjectId,
                List.of(conversationId, hiddenConversation))).thenReturn(List.of(
                new DirectPeerPresenceRow(conversationId, peerId, true, lastSeenAt),
                new DirectPeerPresenceRow(hiddenConversation, UUID.randomUUID(), false, lastSeenAt)));

        Map<UUID, InboxEntry.PeerPresence> result = presenceService.peerPresence(
                subjectId, List.of(conversationId, hiddenConversation));

        assertThat(result).containsEntry(conversationId, new InboxEntry.PeerPresence(false, lastSeenAt));
        assertThat(result).doesNotContainKey(hiddenConversation);
    }
}
