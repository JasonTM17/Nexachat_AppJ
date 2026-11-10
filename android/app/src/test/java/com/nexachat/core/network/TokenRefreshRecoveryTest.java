package com.nexachat.core.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.network.dto.AuthResponse;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.core.storage.PendingRefresh;
import com.nexachat.core.storage.RecallWindowConfig;
import com.nexachat.core.storage.SessionSnapshot;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Provider;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;

/** Idempotent key persistence and ambiguous refresh failure recovery. */
public final class TokenRefreshRecoveryTest {

    @Test
    public void ioAfterCommitRetriesSameRefreshTokenAndRequestKey() throws Exception {
        Fixture fixture = fixture("r0");
        AuthApi api = mock(AuthApi.class);
        Call<AuthResponse> call = mock(Call.class);
        AtomicReference<String> capturedKey = new AtomicReference<>();
        when(api.refresh(anyString(), any())).thenAnswer(invocation -> {
            capturedKey.set(invocation.getArgument(0));
            return call;
        });
        when(call.execute())
                .thenThrow(new IOException("lost response"))
                .thenReturn(Response.success(success("r1")));
        TokenRefresher refresher = new TokenRefresher(fixture.store, new SessionEvents(), (Provider<AuthApi>) () -> api);
        assertSame(TokenRefreshOutcome.TransientFailure.INSTANCE, refresher.refreshBlocking());
        PendingRefresh pending = new PendingRefresh(capturedKey.get(), "r0", 1L);
        when(fixture.store.readPendingRefresh()).thenReturn(Maybe.just(pending));
        assertTrue(refresher.refresh().blockingGet() instanceof TokenRefreshOutcome.FreshToken);
        verify(api, times(2)).refresh(eq(capturedKey.get()), any());
        assertEquals("r1", fixture.state.get().getRefreshToken());
    }

    @Test
    public void markerSaveFailurePreventsDispatch() {
        Fixture fixture = fixture("r0");
        when(fixture.store.reservePendingRefresh(anyLong(), anyString(), anyString()))
                .thenReturn(Single.just(false));
        AuthApi api = mock(AuthApi.class);
        TokenRefresher refresher = new TokenRefresher(fixture.store, new SessionEvents(), (Provider<AuthApi>) () -> api);
        assertSame(TokenRefreshOutcome.TransientFailure.INSTANCE, refresher.refreshBlocking());
        verify(api, times(0)).refresh(anyString(), any());
    }

    @Test
    public void refreshServiceUnavailableIsTransientAndPreservesTokens() throws Exception {
        Fixture fixture = fixture("r0");
        AuthApi api = mock(AuthApi.class);
        Call<AuthResponse> call = mock(Call.class);
        okhttp3.ResponseBody body = okhttp3.ResponseBody.create(
                okhttp3.MediaType.parse("application/json"),
                "{\"error\":{\"code\":\"REFRESH_SERVICE_UNAVAILABLE\"}}");
        when(api.refresh(anyString(), any())).thenReturn(call);
        when(call.execute()).thenReturn(Response.error(503, body));
        TokenRefresher refresher = new TokenRefresher(fixture.store, new SessionEvents(), (Provider<AuthApi>) () -> api);
        assertSame(TokenRefreshOutcome.TransientFailure.INSTANCE, refresher.refreshBlocking());
        assertEquals("r0", fixture.state.get().getRefreshToken());
    }

    private static AuthResponse success(String refresh) {
        return new AuthResponse(new UserDto("u1", "+84900000001", "User"), "a1", 900, refresh);
    }

    private static final class Fixture {
        final TokenStore store;
        final AtomicReference<Tokens> state;

        Fixture(TokenStore store, AtomicReference<Tokens> state) {
            this.store = store;
            this.state = state;
        }
    }

    private static Fixture fixture(String refresh) {
        TokenStore store = mock(TokenStore.class);
        AtomicReference<Tokens> state = new AtomicReference<>(new Tokens("a0", 0L, refresh));
        when(store.readTokens()).thenAnswer(ignored -> Maybe.defer(() -> Maybe.just(state.get())));
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
        when(store.getHasValidRefresh()).thenReturn(Flowable.just(true));
        when(store.readSessionSnapshot())
                .thenReturn(Single.just(new SessionSnapshot(1L, SessionSnapshot.Lifecycle.ACTIVE)));
        when(store.bootstrapLegacySessionIfNeeded()).thenReturn(Single.just(1L));
        when(store.readPendingRefresh()).thenReturn(Maybe.empty());
        when(store.reservePendingRefresh(anyLong(), anyString(), anyString())).thenReturn(Single.just(true));
        when(store.reserveSessionClosing(anyLong())).thenReturn(Single.just(2L));
        when(store.markSessionClosed(anyLong())).thenReturn(Completable.complete());
        return new Fixture(store, state);
    }
}
