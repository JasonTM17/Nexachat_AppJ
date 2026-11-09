package com.nexachat.core.network.stomp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
    public void expiredAccessRefreshesBeforeConnecting() {
        TestScheduler scheduler = new TestScheduler();
        TokenRefresher refresher = mock(TokenRefresher.class);
        when(refresher.refreshIfNeeded("expired-access"))
                .thenReturn(Single.just(Optional.of("fresh-access")));
        AtomicInteger connects = new AtomicInteger();
        RealtimeSession session = session(loggedInStore(0L), failedClient(connects),
                BehaviorProcessor.createDefault(false), BehaviorProcessor.createDefault(false), refresher, scheduler);
        scheduler.triggerActions();
        verify(refresher, times(1)).refreshIfNeeded("expired-access");
        assertEquals(1, connects.get());
    }

    @Test
    public void logoutCancelsLoopAndClosesSocket() {
        TestScheduler scheduler = new TestScheduler();
        BehaviorProcessor<Boolean> loggedIn = BehaviorProcessor.createDefault(true);
        TokenStore store = loggedInStore(Long.MAX_VALUE, loggedIn);
        StompClient stomp = mock(StompClient.class);
        when(stomp.getFrames()).thenReturn(Flowable.empty());
        when(stomp.connect()).thenReturn(Flowable.concat(
                Flowable.just(StompClient.State.Open.INSTANCE), Flowable.never()));
        RealtimeSession session = session(store, stomp, BehaviorProcessor.createDefault(false),
                BehaviorProcessor.createDefault(false), mock(TokenRefresher.class), scheduler);
        scheduler.triggerActions();
        assertTrue(session.isCurrentlyOpen());
        loggedIn.onNext(false);
        scheduler.triggerActions();
        assertFalse(session.isCurrentlyOpen());
        verify(stomp, times(1)).close();
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
    public void authFailureRefreshesOnceAndLogsOutWhenNoFreshTokenArrives() {
        TestScheduler scheduler = new TestScheduler();
        TokenRefresher refresher = mock(TokenRefresher.class);
        when(refresher.refreshIfNeeded("access")).thenReturn(Single.just(Optional.empty()));
        when(refresher.logOut()).thenReturn(Completable.complete());
        AtomicInteger connects = new AtomicInteger();
        StompClient stomp = mock(StompClient.class);
        when(stomp.getFrames()).thenReturn(Flowable.empty());
        when(stomp.connect()).thenAnswer(ignored -> {
            connects.incrementAndGet();
            return Flowable.just(new StompClient.State.Failed("HTTP_401", true));
        });
        RealtimeSession session = session(loggedInStore(Long.MAX_VALUE), stomp,
                BehaviorProcessor.createDefault(false), BehaviorProcessor.createDefault(false),
                refresher, scheduler);

        scheduler.triggerActions();
        scheduler.advanceTimeBy(5, TimeUnit.MINUTES);

        verify(refresher, times(1)).refreshIfNeeded("access");
        verify(refresher, times(1)).logOut();
        assertEquals("a rejected refresh must stop the reconnect loop", 1, connects.get());
    }

    @Test
    public void authFailureRetriesOnceWithARefreshedTokenThenStopsOnASecondRejection() {
        TestScheduler scheduler = new TestScheduler();
        TokenRefresher refresher = mock(TokenRefresher.class);
        when(refresher.refreshIfNeeded("access"))
                .thenReturn(Single.just(Optional.of("fresh-access")));
        when(refresher.logOut()).thenReturn(Completable.complete());
        AtomicInteger connects = new AtomicInteger();
        StompClient stomp = mock(StompClient.class);
        when(stomp.getFrames()).thenReturn(Flowable.empty());
        when(stomp.connect()).thenAnswer(ignored -> {
            connects.incrementAndGet();
            return Flowable.just(new StompClient.State.Failed("HTTP_401", true));
        });
        RealtimeSession session = session(loggedInStore(Long.MAX_VALUE), stomp,
                BehaviorProcessor.createDefault(false), BehaviorProcessor.createDefault(false),
                refresher, scheduler);

        scheduler.triggerActions();
        scheduler.advanceTimeBy(5, TimeUnit.MINUTES);
        scheduler.triggerActions();

        verify(refresher, times(1)).refreshIfNeeded("access");
        verify(refresher, times(1)).logOut();
        assertEquals("refresh is attempted once per auth failure streak", 2, connects.get());
    }

    @Test
    public void connectTimeoutTreatedAsAuthSuspectRefreshesAndStopsWhenRefreshFails() {
        TestScheduler scheduler = new TestScheduler();
        TokenRefresher refresher = mock(TokenRefresher.class);
        when(refresher.refreshIfNeeded("access")).thenReturn(Single.just(Optional.empty()));
        when(refresher.logOut()).thenReturn(Completable.complete());
        AtomicInteger connects = new AtomicInteger();
        StompClient stomp = mock(StompClient.class);
        when(stomp.getFrames()).thenReturn(Flowable.empty());
        when(stomp.connect()).thenAnswer(ignored -> {
            connects.incrementAndGet();
            return Flowable.just(new StompClient.State.Failed(StompClient.REASON_CONNECT_TIMEOUT));
        });
        RealtimeSession session = session(loggedInStore(Long.MAX_VALUE), stomp,
                BehaviorProcessor.createDefault(false), BehaviorProcessor.createDefault(false),
                refresher, scheduler);

        scheduler.triggerActions();
        scheduler.advanceTimeBy(5, TimeUnit.MINUTES);

        verify(refresher, times(1)).refreshIfNeeded("access");
        verify(refresher, times(1)).logOut();
        assertEquals(1, connects.get());
    }

    private static RealtimeSession session(
            TokenStore store,
            StompClient stomp,
            Flowable<Boolean> online,
            Flowable<Boolean> foreground,
            TokenRefresher refresher,
            TestScheduler scheduler) {
        NetworkMonitor monitor = mock(NetworkMonitor.class);
        when(monitor.getIsOnline()).thenReturn(online);
        AppForegroundState foregroundState = () -> foreground;
        return new RealtimeSession(stomp, store, refresher, new Moshi.Builder().build(),
                new ReconnectPolicy(), monitor, foregroundState, scheduler);
    }

    private static StompClient failedClient(AtomicInteger count) {
        StompClient stomp = mock(StompClient.class);
        when(stomp.getFrames()).thenReturn(Flowable.empty());
        when(stomp.connect()).thenAnswer(ignored -> {
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
