package com.nexachat.core.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexachat.core.network.dto.AuthResponse;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.observers.TestObserver;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Provider;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;

/** Only definitive refresh rejection clears the session; transient failures preserve it. */
public final class TokenRefresherTest {

    @Test public void rateLimitPreservesTokens() { assertPreserved(429); }
    @Test public void requestTimeoutPreservesTokens() { assertPreserved(408); }
    @Test public void serverErrorPreservesTokens() { assertPreserved(503); }

    @Test
    public void ioExceptionPreservesTokensAndDoesNotLogOut() throws Exception {
        Harness harness = new Harness();
        Call<AuthResponse> call = mock(Call.class);
        when(harness.api.refresh(any())).thenReturn(call);
        when(call.execute()).thenThrow(new IOException("offline"));
        assertSame(TokenRefreshOutcome.TransientFailure.INSTANCE, harness.refresher.refreshBlocking());
        assertEquals("rt-live", harness.current().getRefreshToken());
        harness.events.getEvents().test().assertNoValues();
    }

    @Test public void reusedRefreshTokenClearsSession() { assertRejected(401); }

    @Test
    public void validationAndForbiddenClearSession() {
        assertRejected(400);
        assertRejected(403);
    }

    @Test
    public void cancellationPropagatesAndKeepsTokens() throws Exception {
        Harness harness = new Harness();
        CancellationException cancelled = new CancellationException("cancelled");
        Call<AuthResponse> call = mock(Call.class);
        when(harness.api.refresh(any())).thenReturn(call);
        when(call.execute()).thenThrow(cancelled);
        try {
            harness.refresher.refreshBlocking();
            fail("cancellation must leave TokenRefresher");
        } catch (CancellationException actual) {
            assertSame(cancelled, actual);
        }
        assertEquals("rt-live", harness.current().getRefreshToken());
        harness.events.getEvents().test().assertNoValues();
    }

    private static void assertPreserved(int status) {
        Harness harness = new Harness();
        Call<AuthResponse> call = responseCall(status);
        when(harness.api.refresh(any())).thenReturn(call);
        assertSame(TokenRefreshOutcome.TransientFailure.INSTANCE, harness.refresher.refreshBlocking());
        assertEquals("access-live", harness.current().getAccessToken());
        assertEquals("rt-live", harness.current().getRefreshToken());
        harness.events.getEvents().test().assertNoValues();
    }

    private static void assertRejected(int status) {
        Harness harness = new Harness();
        when(harness.api.refresh(any())).thenReturn(responseCall(status));
        assertSame(TokenRefreshOutcome.Rejected.INSTANCE, harness.refresher.refreshBlocking());
        assertNull(harness.current());
        harness.events.getEvents().test().assertValue(SessionEvents.Event.LoggedOut.INSTANCE);
    }

    private static Call<AuthResponse> responseCall(int status) {
        ResponseBody error = ResponseBody.create(MediaType.parse("application/json"),
                "{\"error\":{\"code\":\"REFRESH_REJECTED\"}}");
        Response<AuthResponse> response = Response.error(status, error);
        return new Call<>() {
            @Override public Response<AuthResponse> execute() { return response; }
            @Override public void enqueue(retrofit2.Callback<AuthResponse> callback) { throw new UnsupportedOperationException(); }
            @Override public boolean isExecuted() { return false; }
            @Override public void cancel() {}
            @Override public boolean isCanceled() { return false; }
            @Override public Call<AuthResponse> clone() { return this; }
            @Override public okhttp3.Request request() {
                return new okhttp3.Request.Builder().url("http://localhost/").build();
            }
            @Override public okio.Timeout timeout() { return okio.Timeout.NONE; }
        };
    }

    private static final class Harness {
        final TokenStore store = newStore(new Tokens("access-live", 9_000L, "rt-live"));
        final SessionEvents events = new SessionEvents();
        final AuthApi api = mock(AuthApi.class);
        final TokenRefresher refresher = new TokenRefresher(store, events, (Provider<AuthApi>) () -> api);
        Tokens current() { return store.readTokens().blockingGet(); }
    }

    private static TokenStore newStore(Tokens initial) {
        TokenStore store = mock(TokenStore.class);
        AtomicReference<Tokens> state = new AtomicReference<>(initial);
        when(store.readTokens()).thenAnswer(ignored -> Maybe.defer(() ->
                state.get() == null ? Maybe.empty() : Maybe.just(state.get())));
        when(store.saveTokens(anyString(), anyLong(), anyString())).thenAnswer(invocation ->
                Completable.fromAction(() -> state.set(new Tokens(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)))));
        when(store.clear()).thenAnswer(ignored -> Completable.fromAction(() -> state.set(null)));
        when(store.getHasValidRefresh()).thenReturn(Flowable.defer(() -> Flowable.just(state.get() != null)));
        return store;
    }
}
