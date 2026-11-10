package com.nexachat.core.network.stomp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.network.AppForegroundState;
import com.nexachat.core.network.NetworkMonitor;
import com.nexachat.core.network.TokenRefreshOutcome;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** Deterministic Java tests for reconnect backoff, re-arm, logout, and refresh preflight. */
public final class RealtimeSessionReArmTest {

    @Test
    public void backoffStaysWithinCapAndRetryBudget() {
        ReconnectPolicy policy = new ReconnectPolicy();
        assertTrue(policy.delayMillis(Integer.MAX_VALUE) <= ReconnectPolicy.MAX_DELAY_MS);
        assertTrue(policy.delayMillis(Integer.MAX_VALUE) >= ReconnectPolicy.MAX_DELAY_MS * 0.8);
        assertTrue(policy.shouldRetry(ReconnectPolicy.MAX_ATTEMPTS - 1));
        assertFalse(policy.shouldRetry(ReconnectPolicy.MAX_ATTEMPTS));
    }

    @Test
    public void networkRegainRearmsParkedLoop() {
        TestScheduler scheduler = new TestScheduler();
        BehaviorProcessor<Boolean> online = BehaviorProcessor.createDefault(false);
        AtomicInteger connects = new AtomicInteger();
        StompClient stomp = failedClient(connects);
        RealtimeSession session = session(loggedInStore(Long.MAX_VALUE), stomp, online,
                BehaviorProcessor.createDefault(false), mock(TokenRefresher.class), scheduler);
        scheduler.advanceTimeBy(2, TimeUnit.MINUTES);
        assertEquals(ReconnectPolicy.MAX_ATTEMPTS, connects.get());
        online.onNext(true);
        scheduler.triggerActions();
        assertTrue(connects.get() > ReconnectPolicy.MAX_ATTEMPTS);
    }

    @Test
    public void explicitEnsureConnectedRearmsParkedLoop() {
        TestScheduler scheduler = new TestScheduler();
        AtomicInteger connects = new AtomicInteger();
        StompClient stomp = failedClient(connects);
        RealtimeSession session = session(loggedInStore(Long.MAX_VALUE), stomp,
                BehaviorProcessor.createDefault(false), BehaviorProcessor.createDefault(false),
                mock(TokenRefresher.class), scheduler);
        scheduler.advanceTimeBy(2, TimeUnit.MINUTES);
        assertEquals(ReconnectPolicy.MAX_ATTEMPTS, connects.get());
        session.ensureConnected();
        scheduler.triggerActions();
        assertTrue(connects.get() > ReconnectPolicy.MAX_ATTEMPTS);
    }

    @Test
    public void foregroundTimerRetriesOnlyWhileForegrounded() {
        TestScheduler scheduler = new TestScheduler();
        BehaviorProcessor<Boolean> foreground = BehaviorProcessor.createDefault(false);
        AtomicInteger connects = new AtomicInteger();
        RealtimeSession session = session(loggedInStore(Long.MAX_VALUE), failedClient(connects),
                BehaviorProcessor.createDefault(false), foreground, mock(TokenRefresher.class), scheduler);
        scheduler.advanceTimeBy(2, TimeUnit.MINUTES);
        assertEquals(ReconnectPolicy.MAX_ATTEMPTS, connects.get());
        foreground.onNext(true);
        scheduler.triggerActions();
        scheduler.advanceTimeBy(RealtimeSession.PARKED_RETRY_BASE_MS - 100L, TimeUnit.MILLISECONDS);
        assertEquals(ReconnectPolicy.MAX_ATTEMPTS, connects.get());
        scheduler.advanceTimeBy(200L, TimeUnit.MILLISECONDS);
        assertEquals(ReconnectPolicy.MAX_ATTEMPTS + 1, connects.get());
        foreground.onNext(false);
        scheduler.advanceTimeBy(25, TimeUnit.SECONDS);
        int stopped = connects.get();
        assertEquals(ReconnectPolicy.MAX_ATTEMPTS * 2, stopped);
        scheduler.advanceTimeBy(5 * RealtimeSession.PARKED_RETRY_MAX_MS, TimeUnit.MILLISECONDS);
        assertEquals(stopped, connects.get());
    }

    @Test
    public void parkedRetryTimerUsesJitteredDelayFromPolicy() {
        TestScheduler scheduler = new TestScheduler();
        BehaviorProcessor<Boolean> foreground = BehaviorProcessor.createDefault(false);
        AtomicInteger connects = new AtomicInteger();
        ReconnectPolicy policyWithTenPercentJitter = new ReconnectPolicy(() -> 0.10);
        RealtimeSession session = session(loggedInStore(Long.MAX_VALUE), failedClient(connects),
                BehaviorProcessor.createDefault(false), foreground, mock(TokenRefresher.class),
                policyWithTenPercentJitter, scheduler);
        scheduler.advanceTimeBy(2, TimeUnit.MINUTES);
        assertEquals(ReconnectPolicy.MAX_ATTEMPTS, connects.get());
        foreground.onNext(true);
        scheduler.triggerActions();

        // 5000ms + 10% jitter = 5500ms delay.
        scheduler.advanceTimeBy(5_400L, TimeUnit.MILLISECONDS);
        assertEquals("must not trigger before jittered delay", ReconnectPolicy.MAX_ATTEMPTS, connects.get());
        scheduler.advanceTimeBy(200L, TimeUnit.MILLISECONDS);
        assertEquals("must trigger after jittered delay", ReconnectPolicy.MAX_ATTEMPTS + 1, connects.get());
    }

    @Test
    public void expiredAccessRefreshesBeforeConnecting() {
        TestScheduler scheduler = new TestScheduler();
        TokenRefresher refresher = mock(TokenRefresher.class);
        when(refresher.refreshOutcomeIfNeeded("expired-access"))
                .thenReturn(Single.just(new TokenRefreshOutcome.FreshToken("fresh-access")));
        AtomicInteger connects = new AtomicInteger();
        RealtimeSession session = session(loggedInStore(0L), failedClient(connects),
                BehaviorProcessor.createDefault(false), BehaviorProcessor.createDefault(false), refresher, scheduler);
        scheduler.triggerActions();
        verify(refresher, times(1)).refreshOutcomeIfNeeded("expired-access");
        assertEquals(1, connects.get());
    }

    @Test
    public void logoutCancelsLoopAndClosesSocket() {
        TestScheduler scheduler = new TestScheduler();
        BehaviorProcessor<Boolean> loggedIn = BehaviorProcessor.createDefault(true);
        BehaviorProcessor<Boolean> admission = BehaviorProcessor.createDefault(true);
        TokenStore store = loggedInStore(Long.MAX_VALUE, loggedIn);
        TokenRefresher refresher = mock(TokenRefresher.class);
        when(refresher.sessionAdmission()).thenReturn(admission);
        when(refresher.sessionEpoch()).thenReturn(1L);
        when(refresher.isSessionCurrent(anyLong())).thenAnswer(
                invocation -> admission.getValue() && invocation.getArgument(0, Long.class) == 1L);
        StompClient stomp = mock(StompClient.class);
        when(stomp.getFrames()).thenReturn(Flowable.empty());
        when(stomp.connect(anyString())).thenReturn(Flowable.concat(
                Flowable.just(StompClient.State.Open.INSTANCE), Flowable.never()));
        RealtimeSession session = session(store, stomp, BehaviorProcessor.createDefault(false),
                BehaviorProcessor.createDefault(false), refresher, scheduler);
        scheduler.triggerActions();
        assertTrue(session.isCurrentlyOpen());
        // Logout closes admission while refresh bytes may still be present, then clears tokens.
        admission.onNext(false);
        scheduler.triggerActions();
        loggedIn.onNext(false);
        scheduler.triggerActions();
        assertFalse(session.isCurrentlyOpen());
        // One close at the admission boundary; one when tokens are cleared.
        verify(stomp, times(2)).close();
    }

    @Test
    public void loggedOutForegroundAndRegainDoNotConnect() {
        TestScheduler scheduler = new TestScheduler();
        BehaviorProcessor<Boolean> online = BehaviorProcessor.createDefault(false);
        BehaviorProcessor<Boolean> foreground = BehaviorProcessor.createDefault(false);
        AtomicInteger connects = new AtomicInteger();
        RealtimeSession session = session(loggedInStore(null, BehaviorProcessor.createDefault(false)), failedClient(connects), online,
                foreground, mock(TokenRefresher.class), scheduler);
        online.onNext(true);
        foreground.onNext(true);
        session.onAppForeground();
        scheduler.advanceTimeBy(5, TimeUnit.MINUTES);
        assertEquals(0, connects.get());
    }

    @Test
    public void definitiveRefreshRejectionStopsTheSessionWithoutAnotherLogout() {
        TestScheduler scheduler = new TestScheduler();
        TokenRefresher refresher = mock(TokenRefresher.class);
        when(refresher.refreshOutcomeIfNeeded("access"))
                .thenReturn(Single.just(TokenRefreshOutcome.Rejected.INSTANCE));
        AtomicInteger connects = new AtomicInteger();
        StompClient stomp = mock(StompClient.class);
        when(stomp.getFrames()).thenReturn(Flowable.empty());
        when(stomp.connect(anyString())).thenAnswer(ignored -> {
            connects.incrementAndGet();
            return Flowable.just(new StompClient.State.Failed("HTTP_401", true));
        });
        RealtimeSession session = session(loggedInStore(Long.MAX_VALUE), stomp,
                BehaviorProcessor.createDefault(false), BehaviorProcessor.createDefault(false),
                refresher, scheduler);

        scheduler.triggerActions();
        scheduler.advanceTimeBy(5, TimeUnit.MINUTES);

        verify(refresher, times(1)).refreshOutcomeIfNeeded("access");
        verify(refresher, never()).logOut();
        assertEquals("a rejected refresh must stop the reconnect loop", 1, connects.get());
    }

    @Test
    public void authFailureRefreshNetworkErrorKeepsSessionAndUsesBackoff() {
        TestScheduler scheduler = new TestScheduler();
        TokenRefresher refresher = mock(TokenRefresher.class);
        when(refresher.refreshOutcomeIfNeeded("access"))
                .thenReturn(Single.just(TokenRefreshOutcome.TransientFailure.INSTANCE));
        AtomicInteger connects = new AtomicInteger();
        StompClient stomp = mock(StompClient.class);
        when(stomp.getFrames()).thenReturn(Flowable.empty());
        when(stomp.connect(anyString())).thenAnswer(ignored -> {
            connects.incrementAndGet();
            return Flowable.just(new StompClient.State.Failed("HTTP_401", true));
        });
        RealtimeSession session = session(loggedInStore(Long.MAX_VALUE), stomp,
                BehaviorProcessor.createDefault(false), BehaviorProcessor.createDefault(false),
                refresher, scheduler);

        scheduler.triggerActions();
        scheduler.advanceTimeBy(2, TimeUnit.MINUTES);

        verify(refresher, times(ReconnectPolicy.MAX_ATTEMPTS)).refreshOutcomeIfNeeded("access");
        verify(refresher, never()).logOut();
        assertEquals("transient auth recovery must follow the bounded backoff budget",
                ReconnectPolicy.MAX_ATTEMPTS, connects.get());
    }

    @Test
    public void connectTimeoutUsesBackoffWithoutRefreshingOrLoggingOut() {
        TestScheduler scheduler = new TestScheduler();
        TokenRefresher refresher = mock(TokenRefresher.class);
        when(refresher.refreshIfNeeded("access")).thenReturn(Single.just(Optional.empty()));
        when(refresher.logOut()).thenReturn(Completable.complete());
        AtomicInteger connects = new AtomicInteger();
        StompClient stomp = mock(StompClient.class);
        when(stomp.getFrames()).thenReturn(Flowable.empty());
        when(stomp.connect(anyString())).thenAnswer(ignored -> {
            connects.incrementAndGet();
            return Flowable.just(new StompClient.State.Failed(StompClient.REASON_CONNECT_TIMEOUT));
        });
        RealtimeSession session = session(loggedInStore(Long.MAX_VALUE), stomp,
                BehaviorProcessor.createDefault(false), BehaviorProcessor.createDefault(false),
                refresher, scheduler);

        scheduler.triggerActions();
        scheduler.advanceTimeBy(5, TimeUnit.MINUTES);

        verify(refresher, never()).refreshIfNeeded(any());
        verify(refresher, never()).logOut();
        assertEquals("connection failures must use the bounded reconnect budget",
                ReconnectPolicy.MAX_ATTEMPTS, connects.get());
    }

    private static RealtimeSession session(
            TokenStore store,
            StompClient stomp,
            Flowable<Boolean> online,
            Flowable<Boolean> foreground,
            TokenRefresher refresher,
            TestScheduler scheduler) {
        return session(store, stomp, online, foreground, refresher,
                new ReconnectPolicy(() -> 0.0), scheduler);
    }

    private static RealtimeSession session(
            TokenStore store,
            StompClient stomp,
            Flowable<Boolean> online,
            Flowable<Boolean> foreground,
            TokenRefresher refresher,
            ReconnectPolicy reconnectPolicy,
            TestScheduler scheduler) {
        NetworkMonitor monitor = mock(NetworkMonitor.class);
        when(monitor.getIsOnline()).thenReturn(online);
        AppForegroundState foregroundState = () -> foreground;
        stubEpochAdmission(refresher);
        return new RealtimeSession(stomp, store, refresher, new Moshi.Builder().build(),
                reconnectPolicy, monitor, foregroundState, scheduler);
    }

    private static void stubEpochAdmission(TokenRefresher refresher) {
        BehaviorProcessor<Boolean> admitted = BehaviorProcessor.createDefault(true);
        when(refresher.sessionAdmission()).thenReturn(admitted);
        when(refresher.sessionEpoch()).thenReturn(1L);
        when(refresher.isSessionCurrent(anyLong())).thenAnswer(
                invocation -> admitted.getValue() && invocation.getArgument(0, Long.class) == 1L);
    }

    private static StompClient failedClient(AtomicInteger count) {
        StompClient stomp = mock(StompClient.class);
        when(stomp.getFrames()).thenReturn(Flowable.empty());
        when(stomp.connect(anyString())).thenAnswer(ignored -> {
            count.incrementAndGet();
            return Flowable.just(new StompClient.State.Failed("offline"));
        });
        return stomp;
    }

    private static TokenStore loggedInStore(long expiresAt) {
        return loggedInStore(expiresAt, BehaviorProcessor.createDefault(true));
    }

    private static TokenStore loggedInStore(Long expiresAt, BehaviorProcessor<Boolean> loggedIn) {
        TokenStore store = mock(TokenStore.class);
        when(store.getHasValidRefresh()).thenReturn(loggedIn);
        when(store.readTokens()).thenAnswer(ignored -> loggedIn.getValue()
                ? Maybe.just(new Tokens(expiresAt != null && expiresAt == 0L
                        ? "expired-access" : "access", expiresAt == null ? 0L : expiresAt, "refresh"))
                : Maybe.empty());
        when(store.clear()).thenAnswer(ignored -> Completable.fromAction(() -> loggedIn.onNext(false)));
        return store;
    }
}
