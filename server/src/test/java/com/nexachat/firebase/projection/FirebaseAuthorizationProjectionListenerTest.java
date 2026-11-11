package com.nexachat.firebase.projection;

import com.nexachat.conversation.ConversationMembershipChangedEvent;
import com.nexachat.firebase.client.FirebaseProviderException;
import com.nexachat.user.UserAccountDeletedEvent;
import com.nexachat.user.UserAccountCreatedEvent;
import com.nexachat.user.UserPresenceVisibilityChangedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

class FirebaseAuthorizationProjectionListenerTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID CONVERSATION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Test
    void removalIsAppliedBeforeConversationReconciliation() {
        FirebaseAuthorizationProjectionService projections = mock(FirebaseAuthorizationProjectionService.class);
        FirebaseProjectionRetryQueue retries = new FirebaseProjectionRetryQueue(
                Clock.systemUTC(), new FirebaseProjectionMetrics(new SimpleMeterRegistry()));
        FirebaseAuthorizationProjectionListener listener =
                new FirebaseAuthorizationProjectionListener(projections, retries);

        listener.onMembershipChanged(new ConversationMembershipChangedEvent(
                CONVERSATION_ID, List.of(USER_ID)));

        var order = inOrder(projections);
        order.verify(projections).revokeMember(CONVERSATION_ID, USER_ID);
        order.verify(projections).reconcileConversation(CONVERSATION_ID);
    }

    @Test
    void failedPrivacyHideIsQueuedAndDoesNotReturnSuccess() {
        FirebaseAuthorizationProjectionService projections = mock(FirebaseAuthorizationProjectionService.class);
        FirebaseProjectionRetryQueue retries = new FirebaseProjectionRetryQueue(Clock.fixed(
                Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC),
                new FirebaseProjectionMetrics(new SimpleMeterRegistry()));
        FirebaseAuthorizationProjectionListener listener =
                new FirebaseAuthorizationProjectionListener(projections, retries);
        doThrow(new IllegalStateException("provider failure")).when(projections).refreshAccount(USER_ID);

        assertThatThrownBy(() -> listener.onPresenceVisibilityChanged(
                new UserPresenceVisibilityChangedEvent(USER_ID, false)))
                .isInstanceOf(FirebaseProviderException.class);
        org.assertj.core.api.Assertions.assertThat(retries.pendingCount()).isEqualTo(1);
    }

    @Test
    void accountCreationAndDeletionEventsReachTheirProjectionHooks() {
        FirebaseAuthorizationProjectionService projections = mock(FirebaseAuthorizationProjectionService.class);
        FirebaseProjectionRetryQueue retries = new FirebaseProjectionRetryQueue(
                Clock.systemUTC(), new FirebaseProjectionMetrics(new SimpleMeterRegistry()));
        FirebaseAuthorizationProjectionListener listener =
                new FirebaseAuthorizationProjectionListener(projections, retries);
        UserAccountDeletedEvent deleted = new UserAccountDeletedEvent(
                USER_ID, List.of(CONVERSATION_ID), List.of(), List.of(), List.of());

        listener.onAccountCreated(new UserAccountCreatedEvent(USER_ID));
        listener.onAccountDeleted(deleted);

        var order = inOrder(projections);
        order.verify(projections).refreshAccount(USER_ID);
        order.verify(projections).deleteAccount(deleted);
    }
}
