package com.nexachat.core.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.network.dto.AuthResponse;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.core.storage.RecallWindowConfig;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Provider;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;

/** Concurrent refresh callers must share one network rotation. */
public final class TokenRefresherSingleFlightTest {

    @Test
    public void concurrentRefreshCallsShareOneNetworkRotation() throws Exception {
        runRepeatedlyUnderContention(TokenRefresherSingleFlightTest::concurrentForcedRefreshUsesSingleFlight);
    }

    private static void concurrentForcedRefreshUsesSingleFlight() throws Exception {
        TokenStore store = store(new Tokens("stale-access", 0L, "rt-live"));
        SessionEvents events = new SessionEvents();
        AuthApi api = mock(AuthApi.class);
        Call<AuthResponse> call = mock(Call.class);
        when(api.refresh(any())).thenReturn(call);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger refreshCalls = new AtomicInteger();
        when(call.execute()).thenAnswer(ignored -> {
            refreshCalls.incrementAndGet();
            entered.countDown();
            assertTrue(release.await(30, TimeUnit.SECONDS));
            return Response.success(new AuthResponse(
                    new UserDto("u1", "+84900000001", "User"),
                    "fresh-access",
                    900,
                    "rt-rotated"));
        });

        TokenRefresher refresher = new TokenRefresher(store, events, (Provider<AuthApi>) () -> api);
        // Both public calls enter singleFlight before either subscription can complete.
        Single<TokenRefreshOutcome> firstFlight = refresher.refresh();
        Single<TokenRefreshOutcome> secondFlight = refresher.refresh();
        TokenRefreshOutcome[] outcomes = subscribeTogether(firstFlight, secondFlight, entered, release);

        assertTrue(outcomes[0] instanceof TokenRefreshOutcome.FreshToken);
        assertEquals(outcomes[0], outcomes[1]);
        assertEquals("fresh-access", ((TokenRefreshOutcome.FreshToken) outcomes[0]).getAccessToken());
        verify(api, times(1)).refresh(any());
        assertEquals(1, refreshCalls.get());
        assertEquals("rt-rotated", store.readTokens().blockingGet().getRefreshToken());
    }

    @Test
    public void concurrentRefreshIfNeededCallsShareOneNetworkRotation() throws Exception {
        runRepeatedlyUnderContention(TokenRefresherSingleFlightTest::concurrentStaleAccessRefreshUsesSingleFlight);
    }

    private static void concurrentStaleAccessRefreshUsesSingleFlight() throws Exception {
        TokenStore store = store(new Tokens("stale-access", 0L, "rt-live"));
        SessionEvents events = new SessionEvents();
        AuthApi api = mock(AuthApi.class);
        Call<AuthResponse> call = mock(Call.class);
        when(api.refresh(any())).thenReturn(call);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger refreshCalls = new AtomicInteger();
        when(call.execute()).thenAnswer(ignored -> {
            refreshCalls.incrementAndGet();
            entered.countDown();
            assertTrue(release.await(30, TimeUnit.SECONDS));
            return Response.success(new AuthResponse(
                    new UserDto("u1", "+84900000001", "User"),
                    "fresh-access",
                    900,
                    "rt-rotated"));
        });

        TokenRefresher refresher = new TokenRefresher(store, events, (Provider<AuthApi>) () -> api);
        String staleAccess = "stale-access";
        // This is the OkHttp/STOMP recovery entry point; capture both callers before subscription.
        Single<TokenRefreshOutcome> firstFlight = refresher.refreshOutcomeIfNeeded(staleAccess);
        Single<TokenRefreshOutcome> secondFlight = refresher.refreshOutcomeIfNeeded(staleAccess);
        TokenRefreshOutcome[] outcomes = subscribeTogether(firstFlight, secondFlight, entered, release);

        assertTrue(outcomes[0] instanceof TokenRefreshOutcome.FreshToken);
        assertEquals(outcomes[0], outcomes[1]);
        assertEquals("fresh-access", ((TokenRefreshOutcome.FreshToken) outcomes[0]).getAccessToken());
        verify(api, times(1)).refresh(any());
        assertEquals(1, refreshCalls.get());
        assertEquals("rt-rotated", store.readTokens().blockingGet().getRefreshToken());
    }

    @Test
    public void lateRefreshIfNeededCallerReusesStoredTokenWithoutNetwork() {
        TokenStore store = store(new Tokens("fresh-access", 9_000L, "rt-rotated"));
        AuthApi api = mock(AuthApi.class);
        TokenRefresher refresher = new TokenRefresher(store, new SessionEvents(), (Provider<AuthApi>) () -> api);

        TokenRefreshOutcome outcome = refresher.refreshOutcomeIfNeeded("stale-access").blockingGet();

        assertEquals("fresh-access", ((TokenRefreshOutcome.FreshToken) outcome).getAccessToken());
        verify(api, times(0)).refresh(any());
    }

    private static TokenRefreshOutcome[] subscribeTogether(
            Single<TokenRefreshOutcome> firstFlight,
            Single<TokenRefreshOutcome> secondFlight,
            CountDownLatch requestEntered,
            CountDownLatch releaseRequest) throws Exception {
        CountDownLatch callersReady = new CountDownLatch(2);
        CountDownLatch startCallers = new CountDownLatch(1);
        AtomicReference<TokenRefreshOutcome> first = new AtomicReference<>();
        AtomicReference<TokenRefreshOutcome> second = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread workerA = subscriber("refresh-caller-a", firstFlight, first, callersReady,
                startCallers, failure);
        Thread workerB = subscriber("refresh-caller-b", secondFlight, second, callersReady,
                startCallers, failure);
        workerA.start();
        workerB.start();

        boolean callersStarted;
        boolean requestStarted = false;
        try {
            callersStarted = callersReady.await(30, TimeUnit.SECONDS);
            startCallers.countDown();
            requestStarted = requestEntered.await(30, TimeUnit.SECONDS);
        } finally {
            startCallers.countDown();
            releaseRequest.countDown();
        }

        workerA.join(30_000);
        workerB.join(30_000);
        assertTrue("both refresh callers should reach the subscription gate", callersStarted);
        assertTrue("the shared refresh request should start", requestStarted);
        assertTrue("refresh callers should complete", !workerA.isAlive() && !workerB.isAlive());
        if (failure.get() != null) {
            throw new AssertionError("refresh caller failed", failure.get());
        }
        return new TokenRefreshOutcome[] {first.get(), second.get()};
    }

    private static Thread subscriber(
            String name,
            Single<TokenRefreshOutcome> flight,
            AtomicReference<TokenRefreshOutcome> result,
            CountDownLatch callersReady,
            CountDownLatch startCallers,
            AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            callersReady.countDown();
            try {
                assertTrue(startCallers.await(30, TimeUnit.SECONDS));
                result.set(flight.blockingGet());
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        }, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void runRepeatedlyUnderContention(CheckedScenario scenario) throws Exception {
        String configured = System.getenv("NEXACHAT_TOKEN_REFRESH_STRESS_ITERATIONS");
        int iterations = configured == null || configured.isBlank() ? 1 : Integer.parseInt(configured);
        assertTrue("stress iteration count must be positive", iterations > 0);

        AtomicBoolean contend = new AtomicBoolean(iterations > 1);
        List<Thread> loadThreads = new ArrayList<>();
        if (contend.get()) {
            for (int index = 0; index < 2; index++) {
                Thread load = new Thread(() -> {
                    while (contend.get()) {
                        Thread.onSpinWait();
                    }
                }, "token-refresh-cpu-load-" + index);
                load.setDaemon(true);
                load.start();
                loadThreads.add(load);
            }
        }

        try {
            for (int run = 0; run < iterations; run++) {
                scenario.run();
            }
        } finally {
            contend.set(false);
            for (Thread load : loadThreads) {
                load.join(5_000);
            }
        }
    }

    @FunctionalInterface
    private interface CheckedScenario {
        void run() throws Exception;
    }

    private static TokenStore store(Tokens initial) {
        TokenStore store = mock(TokenStore.class);
        AtomicReference<Tokens> state = new AtomicReference<>(initial);
        when(store.readTokens()).thenAnswer(ignored -> Maybe.defer(() -> {
            Tokens current = state.get();
            return current == null ? Maybe.empty() : Maybe.just(current);
        }));
        when(store.saveSession(anyString(), anyLong(), anyString(), any(RecallWindowConfig.class)))
                .thenAnswer(invocation -> Completable.fromAction(() -> state.set(new Tokens(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2)))));
        when(store.clear()).thenAnswer(ignored -> Completable.fromAction(() -> state.set(null)));
        when(store.getHasValidRefresh()).thenReturn(Flowable.defer(() ->
                Flowable.just(state.get() != null)));
        return store;
    }
}
