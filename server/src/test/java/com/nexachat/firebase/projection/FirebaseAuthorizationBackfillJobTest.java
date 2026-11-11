package com.nexachat.firebase.projection;

import com.nexachat.conversation.Conversation;
import com.nexachat.conversation.ConversationRepository;
import com.nexachat.firebase.client.FirebaseRtdbClient;
import com.nexachat.user.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FirebaseAuthorizationBackfillJobTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CONVERSATION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void disablesRulesUntilKeysetAccountAndDirectReaderBackfillCompletes() {
        UserRepository users = mock(UserRepository.class);
        ConversationRepository conversations = mock(ConversationRepository.class);
        FirebaseAuthorizationProjectionService projections = mock(FirebaseAuthorizationProjectionService.class);
        FirebaseRtdbClient rtdb = mock(FirebaseRtdbClient.class);
        when(users.findIdsAfter(null, PageRequest.of(0, FirebaseAuthorizationBackfillJob.PAGE_SIZE)))
                .thenReturn(List.of(USER_ID));
        when(users.findIdsAfter(USER_ID, PageRequest.of(0, FirebaseAuthorizationBackfillJob.PAGE_SIZE)))
                .thenReturn(List.of());
        when(conversations.findIdsAfterByType(null, Conversation.Type.DIRECT,
                PageRequest.of(0, FirebaseAuthorizationBackfillJob.PAGE_SIZE))).thenReturn(List.of(CONVERSATION_ID));
        when(conversations.findIdsAfterByType(CONVERSATION_ID, Conversation.Type.DIRECT,
                PageRequest.of(0, FirebaseAuthorizationBackfillJob.PAGE_SIZE))).thenReturn(List.of());

        new FirebaseAuthorizationBackfillJob(users, conversations, projections, rtdb,
                new FirebaseProjectionMetrics(new SimpleMeterRegistry())).backfill();

        InOrder order = inOrder(rtdb, projections);
        order.verify(rtdb).applyAuthorizationProjection(Map.of("control/enabled", false));
        order.verify(rtdb).presencePrivacyMigrationComplete();
        order.verify(rtdb).removeLegacyOfflinePresenceEntries(USER_ID, 100);
        order.verify(projections).refreshAccount(USER_ID);
        order.verify(projections).reconcileConversation(CONVERSATION_ID);
        order.verify(rtdb).applyAuthorizationProjection(Map.of(
                "control/enabled", true, "control/presencePrivacyVersion", 2));
    }

    @Test
    void skipsOneTimePresenceCleanupAfterPrivacyVersionIsPublished() {
        UserRepository users = mock(UserRepository.class);
        ConversationRepository conversations = mock(ConversationRepository.class);
        FirebaseAuthorizationProjectionService projections = mock(FirebaseAuthorizationProjectionService.class);
        FirebaseRtdbClient rtdb = mock(FirebaseRtdbClient.class);
        when(rtdb.presencePrivacyMigrationComplete()).thenReturn(true);
        when(users.findIdsAfter(null, PageRequest.of(0, FirebaseAuthorizationBackfillJob.PAGE_SIZE)))
                .thenReturn(List.of(USER_ID));
        when(users.findIdsAfter(USER_ID, PageRequest.of(0, FirebaseAuthorizationBackfillJob.PAGE_SIZE)))
                .thenReturn(List.of());
        when(conversations.findIdsAfterByType(null, Conversation.Type.DIRECT,
                PageRequest.of(0, FirebaseAuthorizationBackfillJob.PAGE_SIZE))).thenReturn(List.of());

        new FirebaseAuthorizationBackfillJob(users, conversations, projections, rtdb,
                new FirebaseProjectionMetrics(new SimpleMeterRegistry())).backfill();

        verify(rtdb, never()).removeLegacyOfflinePresenceEntries(USER_ID, 100);
        verify(rtdb).applyAuthorizationProjection(Map.of("control/enabled", true));
    }

    @Test
    void failedBackfillKeepsFirebaseAdmissionDisabled() {
        UserRepository users = mock(UserRepository.class);
        ConversationRepository conversations = mock(ConversationRepository.class);
        FirebaseAuthorizationProjectionService projections = mock(FirebaseAuthorizationProjectionService.class);
        FirebaseRtdbClient rtdb = mock(FirebaseRtdbClient.class);
        when(users.findIdsAfter(null, PageRequest.of(0, FirebaseAuthorizationBackfillJob.PAGE_SIZE)))
                .thenReturn(List.of(USER_ID));
        org.mockito.Mockito.doThrow(new IllegalStateException("test failure"))
                .when(projections).refreshAccount(USER_ID);

        assertThatThrownBy(() -> new FirebaseAuthorizationBackfillJob(
                users, conversations, projections, rtdb,
                new FirebaseProjectionMetrics(new SimpleMeterRegistry())).backfill())
                .isInstanceOf(IllegalStateException.class);

        verify(rtdb).applyAuthorizationProjection(Map.of("control/enabled", false));
        verify(rtdb, never()).applyAuthorizationProjection(Map.of("control/enabled", true));
    }
}
