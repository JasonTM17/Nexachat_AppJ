package com.nexachat.firebase.auth;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.auth.AccessTokensRevokedEvent;
import com.nexachat.conversation.ConversationMemberRepository;
import com.nexachat.firebase.client.FirebaseRtdbClient;
import com.nexachat.firebase.config.FirebaseDatabaseUrl;
import com.nexachat.firebase.projection.FirebaseProjectionMetrics;
import com.nexachat.firebase.projection.FirebaseProjectionRetryQueue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.nexachat.user.User;
import com.nexachat.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FirebaseRealtimeAuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");
    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID BRIDGE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID CONVERSATION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private final UserRepository users = mock(UserRepository.class);
    private final User user = mock(User.class);
    private final ConversationMemberRepository members = mock(ConversationMemberRepository.class);
    private final FirebaseAdminClient admin = mock(FirebaseAdminClient.class);
    private final FirebaseRtdbClient rtdb = mock(FirebaseRtdbClient.class);
    private final FirebaseBridgeLock locks = new FirebaseBridgeLock();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private FirebaseRealtimeAuthService service;

    @BeforeEach
    void setUp() {
        service = new FirebaseRealtimeAuthService(
                users,
                members,
                admin,
                rtdb,
                locks,
                FirebaseDatabaseUrl.parse("https://nexachat.firebaseio.com"),
                clock);
        when(users.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        when(user.getTokensValidAfter()).thenReturn(null);
        when(admin.createCustomToken(eq(USER_ID.toString()), anyMap())).thenReturn("signed-custom-token");
    }

    @Test
    void issuesBridgeClaimAndA120SecondLeaseCappedByTheNexaTokenExpiry() {
        JwtPrincipal principal = principal(NOW.plusSeconds(45));

        FirebaseTokenResponse response = service.issue(principal);

        assertThat(response.customToken()).isEqualTo("signed-custom-token");
        assertThat(response.databaseUrl()).isEqualTo("https://nexachat.firebaseio.com");
        assertThat(response.transport()).isEqualTo("FIREBASE");
        assertThat(response.bridgeId()).isNotNull();
        assertThat(response.leaseUntil()).isEqualTo(NOW.plusSeconds(45));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> claims = ArgumentCaptor.forClass(Map.class);
        verify(admin).createCustomToken(eq(USER_ID.toString()), claims.capture());
        assertThat(claims.getValue()).containsEntry("bridgeId", response.bridgeId().toString());
        verify(rtdb).writeSessionLease(USER_ID, response.bridgeId(), response.leaseUntil().toEpochMilli(), Map.of());
    }

    @Test
    void capsLongLivedNexaTokensAtThe120SecondBridgeLease() {
        FirebaseTokenResponse response = service.issue(principal(NOW.plusSeconds(900)));

        assertThat(response.leaseUntil()).isEqualTo(NOW.plusSeconds(120));
        verify(rtdb).writeSessionLease(USER_ID, response.bridgeId(), NOW.plusSeconds(120).toEpochMilli(), Map.of());
    }

    @Test
    void refusesAStaleCutoffTokenBeforeSigningOrWriting() {
        when(user.getTokensValidAfter()).thenReturn(NOW.minusSeconds(1));

        assertThatThrownBy(() -> service.issue(principal(NOW.plusSeconds(900))))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo(ApiErrorCode.TOKEN_INVALID);

        verify(admin, never()).createCustomToken(eq(USER_ID.toString()), anyMap());
        verify(rtdb, never()).writeSessionLease(eq(USER_ID), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), anyMap());
    }

    @Test
    void refusesATokenIssuedAtTheInclusiveCutoffBeforeSigningOrWriting() {
        when(user.getTokensValidAfter()).thenReturn(NOW.minusSeconds(10));

        assertInvalidToken(() -> service.issue(new JwtPrincipal(
                USER_ID, NOW.minusSeconds(10), NOW.plusSeconds(900))));

        verify(admin, never()).createCustomToken(eq(USER_ID.toString()), anyMap());
        verify(rtdb, never()).writeSessionLease(eq(USER_ID), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), anyMap());
    }

    @Test
    void refusesATombstonedAccountBeforeSigningOrWriting() {
        when(user.getDeletedAt()).thenReturn(NOW.minusSeconds(1));

        assertInvalidToken(() -> service.issue(principal(NOW.plusSeconds(900))));

        verify(admin, never()).createCustomToken(eq(USER_ID.toString()), anyMap());
        verify(rtdb, never()).writeSessionLease(eq(USER_ID), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyLong(), anyMap());
    }

    @Test
    void refusesMissingUsersAndExpiredJwtClaims() {
        when(users.findByIdForUpdate(USER_ID)).thenReturn(Optional.empty());
        assertInvalidToken(() -> service.issue(principal(NOW.plusSeconds(900))));

        when(users.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> service.issue(principal(NOW)))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo(ApiErrorCode.TOKEN_EXPIRED);
        verify(admin, never()).createCustomToken(eq(USER_ID.toString()), anyMap());
    }

    @Test
    void renewsOnlyAnUnexpiredBridgeAndCurrentMemberships() {
        when(rtdb.readSessionLease(USER_ID, BRIDGE_ID)).thenReturn(NOW.plusSeconds(20).toEpochMilli());
        when(members.existsByConversation_IdAndUser_Id(CONVERSATION_ID, USER_ID)).thenReturn(true);
        FirebaseLeaseRequest request = new FirebaseLeaseRequest(BRIDGE_ID, List.of(CONVERSATION_ID));

        FirebaseLeaseResponse response = service.renew(principal(NOW.plusSeconds(900)), request);

        assertThat(response.transport()).isEqualTo("FIREBASE");
        assertThat(response.leaseUntil()).isEqualTo(NOW.plusSeconds(120));
        verify(rtdb).writeSessionLease(
                USER_ID,
                BRIDGE_ID,
                NOW.plusSeconds(120).toEpochMilli(),
                Map.of(CONVERSATION_ID, NOW.plusSeconds(120).toEpochMilli()));
        verify(admin, never()).createCustomToken(eq(USER_ID.toString()), anyMap());
    }

    @Test
    void doesNotResurrectAnExpiredOrRevokedBridge() {
        when(rtdb.readSessionLease(USER_ID, BRIDGE_ID)).thenReturn(NOW.toEpochMilli());

        assertInvalidToken(() -> service.renew(
                principal(NOW.plusSeconds(900)), new FirebaseLeaseRequest(BRIDGE_ID, List.of())));

        verify(rtdb, never()).writeSessionLease(eq(USER_ID), eq(BRIDGE_ID),
                org.mockito.ArgumentMatchers.anyLong(), anyMap());
    }

    @Test
    void deniesLeaseRenewalForAConversationTheUserNoLongerBelongsTo() {
        when(rtdb.readSessionLease(USER_ID, BRIDGE_ID)).thenReturn(NOW.plusSeconds(20).toEpochMilli());
        when(members.existsByConversation_IdAndUser_Id(CONVERSATION_ID, USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.renew(
                principal(NOW.plusSeconds(900)), new FirebaseLeaseRequest(BRIDGE_ID, List.of(CONVERSATION_ID))))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo(ApiErrorCode.NOT_MEMBER);

        verify(rtdb, never()).writeSessionLease(eq(USER_ID), eq(BRIDGE_ID),
                org.mockito.ArgumentMatchers.anyLong(), anyMap());
    }

    @Test
    void bridgeDeleteIsScopedToTheAuthenticatedUserAndBridge() {
        service.revokeBridge(principal(NOW.plusSeconds(900)), BRIDGE_ID);

        verify(rtdb).deleteBridge(USER_ID, BRIDGE_ID);
        verify(rtdb, never()).revokeUser(eq(USER_ID), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void revocationWaitsForAnInFlightIssueAndDeletesItsGrantBeforeReturning() throws Exception {
        CountDownLatch writeEntered = new CountDownLatch(1);
        CountDownLatch allowWriteToFinish = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> order = new ConcurrentLinkedQueue<>();
        AtomicReference<Throwable> threadFailure = new AtomicReference<>();
        doAnswer(invocation -> {
            order.add("write-start");
            writeEntered.countDown();
            if (!allowWriteToFinish.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to release the fake RTDB write");
            }
            order.add("write-finished");
            return null;
        }).when(rtdb).writeSessionLease(any(UUID.class), any(UUID.class),
                org.mockito.ArgumentMatchers.anyLong(), anyMap());
        when(members.findConversationIdsByUserId(USER_ID)).thenReturn(List.of(CONVERSATION_ID));
        doAnswer(invocation -> {
            order.add("user-revoked");
            return null;
        }).when(rtdb).revokeUser(eq(USER_ID), anyList());

        FirebaseSessionRevocationListener listener =
                new FirebaseSessionRevocationListener(locks, rtdb, admin, members,
                        new FirebaseProjectionRetryQueue(clock, new FirebaseProjectionMetrics(new SimpleMeterRegistry())));
        Thread issueThread = new Thread(() -> runCapturingFailure(
                () -> service.issue(principal(NOW.plusSeconds(900))), threadFailure), "firebase-issue-test");
        Thread revokeThread = new Thread(() -> runCapturingFailure(
                () -> listener.onAccessTokensRevoked(new AccessTokensRevokedEvent(USER_ID)), threadFailure),
                "firebase-revoke-test");
        issueThread.setDaemon(true);
        revokeThread.setDaemon(true);

        issueThread.start();
        assertThat(writeEntered.await(5, TimeUnit.SECONDS)).isTrue();
        revokeThread.start();
        try {
            assertThat(awaitQueuedRevoker(locks, revokeThread)).isTrue();
        } finally {
            allowWriteToFinish.countDown();
            issueThread.join(5000);
            revokeThread.join(5000);
        }

        assertThat(issueThread.isAlive()).isFalse();
        assertThat(revokeThread.isAlive()).isFalse();
        assertThat(threadFailure.get()).isNull();
        assertThat(order).containsExactly("write-start", "write-finished", "user-revoked");
        verify(admin).revokeRefreshTokens(USER_ID.toString());
    }

    private static JwtPrincipal principal(Instant expiresAt) {
        return new JwtPrincipal(USER_ID, NOW.minusSeconds(10), expiresAt);
    }

    private static void assertInvalidToken(ThrowingCallable operation) {
        assertThatThrownBy(operation)
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getCode())
                .isEqualTo(ApiErrorCode.TOKEN_INVALID);
    }

    private static boolean awaitQueuedRevoker(FirebaseBridgeLock locks, Thread revokeThread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (locks.lockFor(USER_ID).hasQueuedThread(revokeThread)) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    private static void runCapturingFailure(Runnable operation, AtomicReference<Throwable> failure) {
        try {
            operation.run();
        } catch (Throwable ex) {
            failure.compareAndSet(null, ex);
        }
    }
}
