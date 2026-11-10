package com.nexachat.core.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.network.dto.AuthResponse;
import com.nexachat.core.network.dto.RefreshRequest;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.core.storage.RecallWindowConfig;
import com.nexachat.core.storage.SessionSnapshot;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Provider;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;

/** Session-epoch fencing for logout, stale flights, and stale cached success. */
public final class TokenSessionLifecycleTest {

    @Test
    public void logoutDuringRefreshBlocksDispatchAndWriteBack() throws Exception {
        Fixture fixture = fixture(new Tokens("stale", 0L, "r0"));
        TokenStore store = fixture.store;
        AuthApi api = mock(AuthApi.class);
        Call<AuthResponse> call = mock(Call.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(api.refresh(anyString(), any())).thenReturn(call);
        when(call.execute()).thenAnswer(ignored -> {
            entered.countDown();
            assertTrue(release.await(30, TimeUnit.SECONDS));
            return Response.success(
                    new AuthResponse(new UserDto("u1", "+84900000001", "User"), "a1", 900, "r1"));
        });
        TokenRefresher refresher = new TokenRefresher(store, new SessionEvents(), (Provider<AuthApi>) () -> api);
        Thread refresh = new Thread(refresher::refreshBlocking);
        refresh.start();
        assertTrue(entered.await(30, TimeUnit.SECONDS));
        refresher.onLogout();
        store.clear().blockingAwait();
        release.countDown();
        refresh.join(30_000);
        assertNull(current(store));
        verify(store, never()).commitRefreshRotation(
                anyLong(), anyString(), anyString(), anyLong(), anyString(), any(RecallWindowConfig.class));
    }

    @Test
    public void unsubscribedPreLogoutFlightCannotDispatchInNewSession() throws Exception {
        Fixture fixture = fixture(new Tokens("stale", 0L, "r0"));
        TokenStore store = fixture.store;
        SessionEvents events = new SessionEvents();
        AuthApi api = mock(AuthApi.class);
        TokenRefresher refresher = new TokenRefresher(store, events, (Provider<AuthApi>) () -> api);
        Single<TokenRefreshOutcome> orphaned = refresher.refresh();
        refresher.onLogout();
        store.clear().blockingAwait();
        fixture.state.set(new Tokens("access-b", 1L, "rB"));
        refresher.onSessionEstablished(2L);
        when(store.readSessionSnapshot())
                .thenReturn(Single.just(new SessionSnapshot(2L, SessionSnapshot.Lifecycle.ACTIVE)));
        verify(api, never()).refresh(anyString(), any());
        assertEquals(
                TokenRefreshOutcome.NoRefreshToken.INSTANCE,
                orphaned.blockingGet());
    }

    @Test
    public void staleCachedSuccessIsNotReplayedAfterLogout() {
        Fixture fixture = fixture(new Tokens("a0", 0L, "r0"));
        TokenStore store = fixture.store;
        AuthApi api = mock(AuthApi.class);
        Call<AuthResponse> call = mock(Call.class);
        when(api.refresh(anyString(), any())).thenReturn(call);
        try {
            when(call.execute()).thenReturn(Response.success(
                    new AuthResponse(new UserDto("u1", "+84900000001", "User"), "a1", 900, "r1")));
        } catch (java.io.IOException impossible) {
            throw new AssertionError(impossible);
        }
        TokenRefresher refresher = new TokenRefresher(store, new SessionEvents(), (Provider<AuthApi>) () -> api);
        assertTrue(refresher.refresh().blockingGet() instanceof TokenRefreshOutcome.FreshToken);
        refresher.onLogout();
        TokenRefreshOutcome second = refresher.refreshOutcomeIfNeeded("a0").blockingGet();
        assertEquals(TokenRefreshOutcome.NoRefreshToken.INSTANCE, second);
    }

    @Test
    public void invalidateDuringTokenReadForcesNoDispatch() {
        TokenStore store = mock(TokenStore.class);
        AtomicReference<Tokens> state = new AtomicReference<>(new Tokens("a0", 0L, "r0"));
        when(store.readTokens()).thenReturn(Maybe.fromCallable(() -> {
            state.set(null);
            return new Tokens("a0", 0L, "r0");
        }));
        stubLifecycle(store);
        AuthApi api = mock(AuthApi.class);
        TokenRefresher refresher = new TokenRefresher(store, new SessionEvents(), (Provider<AuthApi>) () -> api);
        refresher.onLogout();
        TokenRefreshOutcome outcome = refresher.refreshBlocking();
        assertEquals(TokenRefreshOutcome.NoRefreshToken.INSTANCE, outcome);
        verify(api, never()).refresh(anyString(), any());
    }

    @Test
    public void sessionEpochApiReflectsAdmissionWithoutIo() {
        TokenStore store = fixture(new Tokens("a0", 0L, "r0")).store;
        TokenRefresher refresher = new TokenRefresher(
                store, new SessionEvents(), (Provider<AuthApi>) () -> mock(AuthApi.class));
        refresher.onSessionEstablished(1L);
        assertTrue(refresher.isSessionCurrent(1L));
        assertFalse(refresher.isSessionCurrent(2L));
        refresher.onLogout();
        assertFalse(refresher.isSessionCurrent(1L));
    }

    @Test
    public void recoveryUnavailablePreservesSessionAndStopsAutomaticRotation() throws Exception {
        TokenStore store = fixture(new Tokens("a0", 0L, "r0")).store;
        AuthApi api = mock(AuthApi.class);
        Call<AuthResponse> call = mock(Call.class);
        okhttp3.ResponseBody body = okhttp3.ResponseBody.create(
                okhttp3.MediaType.parse("application/json"),
                "{\"error\":{\"code\":\"REFRESH_RECOVERY_UNAVAILABLE\"}}");
        when(api.refresh(anyString(), any())).thenReturn(call);
        when(call.execute()).thenReturn(Response.error(409, body));
        TokenRefresher refresher = new TokenRefresher(store, new SessionEvents(), (Provider<AuthApi>) () -> api);
        assertEquals(
                TokenRefreshOutcome.RecoveryRequired.INSTANCE, refresher.refreshBlocking());
        assertEquals("r0", current(store).getRefreshToken());
        new SessionEvents().getEvents().test().assertNoValues();
    }

    @Test
    public void sameRequestKeyIsReusedWhenPendingMarkerExists() throws Exception {
        TokenStore store = fixture(new Tokens("a0", 0L, "r0")).store;
        AtomicInteger reserveCalls = new AtomicInteger();
        AtomicReference<String> reservedKey = new AtomicReference<>();
        when(store.reservePendingRefresh(anyLong(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(2);
                    if (reservedKey.get() == null) {
                        reservedKey.set(key);
                    } else if (!reservedKey.get().equals(key)) {
                        return Single.just(false);
                    }
                    reserveCalls.incrementAndGet();
                    return Single.just(true);
                });
        when(store.readPendingRefresh()).thenAnswer(ignored -> {
            if (reservedKey.get() == null) {
                return Maybe.empty();
            }
            return Maybe.just(new com.nexachat.core.storage.PendingRefresh(
                    reservedKey.get(), "r0", 1L));
        });
        AuthApi api = mock(AuthApi.class);
        Call<AuthResponse> call = mock(Call.class);
        when(api.refresh(anyString(), any())).thenReturn(call);
        when(call.execute()).thenThrow(new java.io.IOException("offline"));
        TokenRefresher refresher = new TokenRefresher(store, new SessionEvents(), (Provider<AuthApi>) () -> api);
        assertSame(TokenRefreshOutcome.TransientFailure.INSTANCE, refresher.refreshBlocking());
        String firstKey = reservedKey.get();
        assertSame(TokenRefreshOutcome.TransientFailure.INSTANCE, refresher.refreshBlocking());
        verify(api, times(2)).refresh(eq(firstKey), any());
        assertEquals(2, reserveCalls.get());
    }

    private static Tokens current(TokenStore store) {
        return store.readTokens().blockingGet();
    }

    private static final class Fixture {
        final TokenStore store;
        final AtomicReference<Tokens> state;

        Fixture(TokenStore store, AtomicReference<Tokens> state) {
            this.store = store;
            this.state = state;
        }
    }

    private static Fixture fixture(Tokens initial) {
        TokenStore store = mock(TokenStore.class);
        AtomicReference<Tokens> state = new AtomicReference<>(initial);
        when(store.readTokens()).thenAnswer(ignored -> Maybe.defer(() -> {
            Tokens tokens = state.get();
            return tokens == null ? Maybe.empty() : Maybe.just(tokens);
        }));
        when(store.commitRefreshRotation(
                        anyLong(),
                        anyString(),
                        anyString(),
                        anyLong(),
                        anyString(),
                        any(RecallWindowConfig.class)))
                .thenAnswer(invocation -> Completable.fromAction(() -> state.set(new Tokens(
                        invocation.getArgument(2),
                        invocation.getArgument(3),
                        invocation.getArgument(4)))));
        when(store.clear()).thenAnswer(ignored -> Completable.fromAction(() -> state.set(null)));
        when(store.getHasValidRefresh()).thenReturn(Flowable.defer(() ->
                Flowable.just(state.get() != null)));
        stubLifecycle(store);
        return new Fixture(store, state);
    }

    private static void stubLifecycle(TokenStore store) {
        when(store.readSessionSnapshot())
                .thenReturn(Single.just(new SessionSnapshot(1L, SessionSnapshot.Lifecycle.ACTIVE)));
        when(store.bootstrapLegacySessionIfNeeded()).thenReturn(Single.just(1L));
        when(store.readPendingRefresh()).thenReturn(Maybe.empty());
        when(store.reservePendingRefresh(anyLong(), anyString(), anyString())).thenReturn(Single.just(true));
        when(store.reserveSessionClosing(anyLong())).thenReturn(Single.just(2L));
        when(store.markSessionClosed(anyLong())).thenReturn(Completable.complete());
    }
}
