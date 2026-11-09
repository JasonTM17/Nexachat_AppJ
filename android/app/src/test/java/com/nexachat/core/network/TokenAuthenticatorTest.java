package com.nexachat.core.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
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
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Provider;
import okhttp3.Protocol;
import okhttp3.Request;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;

public final class TokenAuthenticatorTest {

    private static final String OLD = "stale-access-token";
    private static final String NEW = "new-access-token";

    @Test
    public void concurrent401sShareOneRefreshAndRetryWithRotatedToken() throws Exception {
        TokenStore store = store(new Tokens(OLD, 0, "rt-stale"));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AuthApi api = mock(AuthApi.class);
        Call<AuthResponse> call = mock(Call.class);
        when(api.refresh(any())).thenReturn(call);
        when(call.execute()).thenAnswer(ignored -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return Response.success(auth(NEW, "rt-new"));
        });
        TokenAuthenticator authenticator = authenticator(store, api, new SessionEvents());
        ConcurrentLinkedQueue<Request> results = new ConcurrentLinkedQueue<>();
        Thread first = new Thread(() -> results.add(authenticate(authenticator, unauthorized(OLD))));
        first.start();
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        Thread second = new Thread(() -> results.add(authenticate(authenticator, unauthorized(OLD))));
        second.start();
        awaitQueued(second);
        release.countDown();
        first.join(10_000);
        second.join(10_000);

        verify(api, times(1)).refresh(new RefreshRequest("rt-stale"));
        assertEquals(2, results.size());
        for (Request request : results) {
            assertEquals("Bearer " + NEW, request.header(AuthInterceptor.HEADER_AUTHORIZATION));
        }
        assertEquals("rt-new", current(store).getRefreshToken());
    }

    @Test
    public void alreadyRotatedAccessTokenRetriesWithoutNetworkCall() throws Exception {
        TokenStore store = store(new Tokens(NEW, Long.MAX_VALUE, "rt-current"));
        AuthApi api = mock(AuthApi.class);
        Request retry = authenticator(store, api, new SessionEvents())
                .authenticate(null, unauthorized(OLD));
        assertEquals("Bearer " + NEW, retry.header(AuthInterceptor.HEADER_AUTHORIZATION));
        verify(api, never()).refresh(any());
    }

    @Test
    public void definitiveRefreshRejectionClearsSessionAndEmitsLogout() throws Exception {
        TokenStore store = store(new Tokens(OLD, 0, "rt-dead"));
        AuthApi api = mock(AuthApi.class);
        Call<AuthResponse> call = mock(Call.class);
        when(api.refresh(any())).thenReturn(call);
        when(call.execute()).thenReturn(Response.error(401,
                okhttp3.ResponseBody.create(okhttp3.MediaType.parse("application/json"),
                        "{\"error\":{\"code\":\"TOKEN_REUSED\"}}")));
        SessionEvents events = new SessionEvents();
        io.reactivex.rxjava3.subscribers.TestSubscriber<SessionEvents.Event> observer = events.getEvents().test();
        Request retry = authenticator(store, api, events).authenticate(null, unauthorized(OLD));
        assertNull(retry);
        assertNull(current(store));
        observer.assertValue(SessionEvents.Event.LoggedOut.INSTANCE);
    }

    @Test
    public void publicAuth401DoesNotAccessStoreOrRefresh() throws Exception {
        TokenStore store = mock(TokenStore.class);
        AuthApi api = mock(AuthApi.class);
        Request request = authenticator(store, api, new SessionEvents()).authenticate(
                null, unauthorized("token", "https://nexachat.test/api/v1/auth/refresh", 0));
        assertNull(request);
        verify(store, never()).readTokens();
        verify(api, never()).refresh(any());
    }

    @Test
    public void chained401LimitStopsRetryStorm() throws Exception {
        TokenStore store = store(new Tokens(OLD, 0, "rt-1"));
        AuthApi api = mock(AuthApi.class);
        assertNull(authenticator(store, api, new SessionEvents())
                .authenticate(null, unauthorized(OLD, "https://nexachat.test/api/v1/users/me", 2)));
        verify(api, never()).refresh(any());
    }

    private static TokenAuthenticator authenticator(TokenStore store, AuthApi api, SessionEvents events) {
        return new TokenAuthenticator(new TokenRefresher(store, events, (Provider<AuthApi>) () -> api));
    }

    private static TokenStore store(Tokens initial) {
        TokenStore store = mock(TokenStore.class);
        AtomicReference<Tokens> value = new AtomicReference<>(initial);
        when(store.readTokens()).thenAnswer(ignored -> Maybe.defer(() ->
                value.get() == null ? Maybe.empty() : Maybe.just(value.get())));
        when(store.saveSession(anyString(), anyLong(), anyString(), any(RecallWindowConfig.class)))
                .thenAnswer(invocation -> {
            value.set(new Tokens(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
            return Completable.complete();
        });
        when(store.clear()).thenAnswer(ignored -> Completable.fromAction(() -> value.set(null)));
        when(store.getHasValidRefresh()).thenReturn(Flowable.defer(() -> Flowable.just(value.get() != null)));
        return store;
    }

    private static Tokens current(TokenStore store) {
        return store.readTokens().blockingGet();
    }

    private static AuthResponse auth(String access, String refresh) {
        return new AuthResponse(new UserDto("user-1", "+84905551122", "IT User"), access, 900, refresh);
    }

    private static okhttp3.Response unauthorized(String bearer) {
        return unauthorized(bearer, "https://nexachat.test/api/v1/users/me", 0);
    }

    private static okhttp3.Response unauthorized(String bearer, String url, int priorCount) {
        Request request = new Request.Builder().url(url)
                .header(AuthInterceptor.HEADER_AUTHORIZATION, AuthInterceptor.BEARER_PREFIX + bearer).build();
        okhttp3.Response response = response(request, null);
        for (int i = 0; i < priorCount; i++) response = response(request, response);
        return response;
    }

    private static okhttp3.Response response(Request request, okhttp3.Response prior) {
        okhttp3.Response.Builder builder = new okhttp3.Response.Builder()
                .request(request).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized");
        if (prior != null) builder.priorResponse(prior);
        return builder.build();
    }

    private static Request authenticate(TokenAuthenticator authenticator, okhttp3.Response response) {
        try {
            Request request = authenticator.authenticate(null, response);
            if (request == null) throw new AssertionError("expected authenticated retry");
            return request;
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void awaitQueued(Thread thread) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.isAlive() && thread.getState() != Thread.State.WAITING
                && thread.getState() != Thread.State.TIMED_WAITING) {
            assertTrue("second caller never queued", System.nanoTime() < deadline);
            Thread.yield();
        }
    }
}
