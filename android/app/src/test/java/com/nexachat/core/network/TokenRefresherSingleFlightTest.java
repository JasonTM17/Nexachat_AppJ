package com.nexachat.core.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
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
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Provider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;

/** Concurrent refresh callers must share one network rotation. */
public final class TokenRefresherSingleFlightTest {

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
        RxJavaPlugins.setComputationSchedulerHandler(ignored -> Schedulers.trampoline());
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
    }

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
    }

    @Test
    public void concurrentRefreshCallsShareOneNetworkRotation() throws Exception {
        for (int run = 0; run < 50; run++) {
            concurrentForcedRefreshUsesSingleFlight();
        }
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
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return Response.success(new AuthResponse(
                    new UserDto("u1", "+84900000001", "User"),
                    "fresh-access",
                    900,
                    "rt-rotated"));
        });

        TokenRefresher refresher = new TokenRefresher(store, events, (Provider<AuthApi>) () -> api);
        List<Throwable> errors = new ArrayList<>();
        AtomicReference<TokenRefreshOutcome> first = new AtomicReference<>();
        AtomicReference<TokenRefreshOutcome> second = new AtomicReference<>();
        CountDownLatch startGate = new CountDownLatch(1);
        CyclicBarrier pairedCallers = new CyclicBarrier(2);

        Thread workerA = new Thread(() -> {
            try {
                assertTrue(startGate.await(10, TimeUnit.SECONDS));
                pairedCallers.await(10, TimeUnit.SECONDS);
                first.set(refresher.refreshBlocking());
            } catch (Throwable error) {
                errors.add(error);
            }
        });
        Thread workerB = new Thread(() -> {
            try {
                assertTrue(startGate.await(10, TimeUnit.SECONDS));
                pairedCallers.await(10, TimeUnit.SECONDS);
                second.set(refresher.refreshBlocking());
            } catch (Throwable error) {
                errors.add(error);
            }
        });

        workerA.start();
        workerB.start();
        startGate.countDown();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        release.countDown();
        workerA.join(10_000);
        workerB.join(10_000);

        assertTrue(errors.isEmpty());
        assertSame(TokenRefreshOutcome.FreshToken.class, first.get().getClass());
        assertEquals(first.get(), second.get());
        assertEquals("fresh-access", ((TokenRefreshOutcome.FreshToken) first.get()).getAccessToken());
        verify(api, times(1)).refresh(any());
        assertEquals(1, refreshCalls.get());
        assertEquals("rt-rotated", store.readTokens().blockingGet().getRefreshToken());
    }

    @Test
    public void concurrentRefreshIfNeededCallsShareOneNetworkRotation() throws Exception {
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
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return Response.success(new AuthResponse(
                    new UserDto("u1", "+84900000001", "User"),
                    "fresh-access",
                    900,
                    "rt-rotated"));
        });

        TokenRefresher refresher = new TokenRefresher(store, events, (Provider<AuthApi>) () -> api);
        List<Throwable> errors = new ArrayList<>();
        AtomicReference<TokenRefreshOutcome> first = new AtomicReference<>();
        AtomicReference<TokenRefreshOutcome> second = new AtomicReference<>();
        CountDownLatch startGate = new CountDownLatch(1);
        CyclicBarrier pairedCallers = new CyclicBarrier(2);
        String staleAccess = "stale-access";

        Thread workerA = new Thread(() -> {
            try {
                assertTrue(startGate.await(10, TimeUnit.SECONDS));
                pairedCallers.await(10, TimeUnit.SECONDS);
                first.set(refresher.refreshOutcomeIfNeeded(staleAccess).blockingGet());
            } catch (Throwable error) {
                errors.add(error);
            }
        });
        Thread workerB = new Thread(() -> {
            try {
                assertTrue(startGate.await(10, TimeUnit.SECONDS));
                pairedCallers.await(10, TimeUnit.SECONDS);
                second.set(refresher.refreshOutcomeIfNeeded(staleAccess).blockingGet());
            } catch (Throwable error) {
                errors.add(error);
            }
        });

        workerA.start();
        workerB.start();
        startGate.countDown();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        release.countDown();
        workerA.join(10_000);
        workerB.join(10_000);

        assertTrue(errors.isEmpty());
        assertSame(TokenRefreshOutcome.FreshToken.class, first.get().getClass());
        assertEquals(first.get(), second.get());
        verify(api, times(1)).refresh(any());
        assertEquals(1, refreshCalls.get());
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
