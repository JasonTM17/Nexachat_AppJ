package com.nexachat.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.network.AuthApi;
import com.nexachat.core.network.HealthApi;
import com.nexachat.core.network.ServerWakeGate;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.TokenRefreshOutcome;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.network.dto.AuthResponse;
import com.nexachat.core.network.dto.GoogleNonceResponse;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.core.storage.RecallWindowConfig;
import com.nexachat.core.storage.SessionSnapshot;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.nexachat.notification.DeviceTokenRegistrationManager;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;

/** Verifies the safe readiness GET completes before each one-time auth mutation. */
public final class ServerWakeAuthPreflightTest {
    private MockWebServer server;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void loginWaitsForHealthThenSendsTheNonIdempotentPostOnce() throws Exception {
        server.enqueue(response(503));
        server.enqueue(response(200));
        TestScheduler retryClock = new TestScheduler();
        ServerWakeGate gate = gate(retryClock);
        AuthApi authApi = mock(AuthApi.class);
        TokenStore tokenStore = mock(TokenStore.class);
        TokenRefresher tokenRefresher = mock(TokenRefresher.class);
        SessionEvents sessionEvents = mock(SessionEvents.class);
        DeviceTokenRegistrationManager deviceTokens = mock(DeviceTokenRegistrationManager.class);
        AuthResponse authResponse = new AuthResponse(
                new UserDto("user-1", "+84901234567", "Son"), "access", 900, "refresh");
        Call<AuthResponse> loginCall = mock(Call.class);
        when(authApi.login(any())).thenReturn(loginCall);
        when(loginCall.execute()).thenReturn(Response.success(authResponse));
        when(tokenStore.establishNewSession(
                        anyString(), anyLong(), anyString(), any(RecallWindowConfig.class)))
                .thenReturn(Single.just(1L));
        when(deviceTokens.onSessionAvailable()).thenReturn(Completable.complete());
        AuthRepository repository = new AuthRepository(
                authApi, tokenStore, sessionEvents, tokenRefresher, deviceTokens, null, gate);
        var starting = gate.getIsStarting().test();

        var result = repository.login("+84901234567", "password123").test();

        RecordedRequest firstHealthRequest = server.takeRequest(2, TimeUnit.SECONDS);
        assertTrue("login should be waiting at health preflight; values=" + result.values(),
                firstHealthRequest != null);
        assertHealthRequest(firstHealthRequest);
        starting.awaitCount(2).assertValues(false, true);
        verify(authApi, never()).login(any());
        retryClock.advanceTimeBy(1L, TimeUnit.SECONDS);
        assertHealthRequest(server.takeRequest(2, TimeUnit.SECONDS));
        result.awaitDone(5L, TimeUnit.SECONDS).assertValue(value -> value instanceof com.nexachat.core.common.Result.Success<?>);
        verify(authApi, times(1)).login(any());
    }

    @Test
    public void refreshWaitsForHealthThenDispatchesOneKeyedRotation() throws Exception {
        server.enqueue(response(503));
        server.enqueue(response(200));
        TestScheduler retryClock = new TestScheduler();
        ServerWakeGate gate = gate(retryClock);
        TokenStore tokenStore = refreshStore();
        AuthApi authApi = mock(AuthApi.class);
        Call<AuthResponse> refreshCall = mock(Call.class);
        AtomicReference<String> dispatchedKey = new AtomicReference<>();
        when(authApi.refresh(anyString(), any())).thenAnswer(invocation -> {
            dispatchedKey.set(invocation.getArgument(0));
            return refreshCall;
        });
        when(refreshCall.execute()).thenReturn(Response.success(
                new AuthResponse(new UserDto("user-1", "+84901234567", "Son"),
                        "new-access", 900, "new-refresh")));
        TokenRefresher refresher = new TokenRefresher(
                tokenStore, mock(SessionEvents.class), () -> authApi, gate);
        var starting = gate.getIsStarting().test();

        var result = refresher.refresh().test();

        RecordedRequest firstHealthRequest = server.takeRequest(2, TimeUnit.SECONDS);
        assertTrue("refresh should be waiting at health preflight; values=" + result.values(),
                firstHealthRequest != null);
        assertHealthRequest(firstHealthRequest);
        starting.awaitCount(2).assertValues(false, true);
        verify(authApi, never()).refresh(anyString(), any());
        retryClock.advanceTimeBy(1L, TimeUnit.SECONDS);
        assertHealthRequest(server.takeRequest(2, TimeUnit.SECONDS));
        result.awaitDone(5L, TimeUnit.SECONDS)
                .assertValue(value -> value instanceof TokenRefreshOutcome.FreshToken);
        verify(authApi, times(1)).refresh(anyString(), any());
        assertTrue("keyed refresh must dispatch its persisted request key", dispatchedKey.get() != null);
        verify(tokenStore, times(1)).reservePendingRefresh(
                anyLong(), eq("r0"), eq(dispatchedKey.get()));
    }

    @Test
    public void replacingSessionDuringWakeWaitBlocksOldRefreshDispatch() throws Exception {
        server.enqueue(response(503));
        server.enqueue(response(200));
        TestScheduler retryClock = new TestScheduler();
        ServerWakeGate gate = gate(retryClock);
        TokenStore tokenStore = refreshStore();
        AuthApi authApi = mock(AuthApi.class);
        TokenRefresher refresher = new TokenRefresher(
                tokenStore, mock(SessionEvents.class), () -> authApi, gate);
        var starting = gate.getIsStarting().test();

        var result = refresher.refresh().test();

        assertHealthRequest(server.takeRequest(2, TimeUnit.SECONDS));
        starting.awaitCount(2).assertValues(false, true);
        refresher.onSessionEstablished(2L);
        retryClock.advanceTimeBy(1L, TimeUnit.SECONDS);
        assertHealthRequest(server.takeRequest(2, TimeUnit.SECONDS));
        result.awaitDone(5L, TimeUnit.SECONDS)
                .assertValue(value -> value == TokenRefreshOutcome.NoRefreshToken.INSTANCE);
        verify(authApi, never()).refresh(anyString(), any());
    }

    @Test
    public void googleSignInPreflightsNonceAndSessionExchange() throws Exception {
        server.enqueue(response(503));
        server.enqueue(response(200));
        server.enqueue(response(503));
        server.enqueue(response(200));
        TestScheduler retryClock = new TestScheduler();
        ServerWakeGate gate = gate(retryClock);
        AuthApi authApi = mock(AuthApi.class);
        TokenStore tokenStore = mock(TokenStore.class);
        TokenRefresher tokenRefresher = mock(TokenRefresher.class);
        DeviceTokenRegistrationManager deviceTokens = mock(DeviceTokenRegistrationManager.class);
        Call<GoogleNonceResponse> nonceCall = mock(Call.class);
        when(authApi.googleNonce()).thenReturn(nonceCall);
        when(nonceCall.execute()).thenReturn(Response.success(new GoogleNonceResponse("nonce", 120L)));
        Call<AuthResponse> signInCall = mock(Call.class);
        when(authApi.googleSignIn(any())).thenReturn(signInCall);
        when(signInCall.execute()).thenReturn(Response.success(
                new AuthResponse(new UserDto("user-1", "+84901234567", "Son"),
                        "access", 900, "refresh")));
        when(tokenStore.establishNewSession(
                        anyString(), anyLong(), anyString(), any(RecallWindowConfig.class)))
                .thenReturn(Single.just(1L));
        when(deviceTokens.onSessionAvailable()).thenReturn(Completable.complete());
        AuthRepository repository = new AuthRepository(
                authApi, tokenStore, mock(SessionEvents.class), tokenRefresher,
                deviceTokens, null, gate);

        var nonceStarting = gate.getIsStarting().test();
        var nonceResult = repository.requestGoogleNonce().test();
        assertHealthRequest(server.takeRequest(2, TimeUnit.SECONDS));
        nonceStarting.awaitCount(2).assertValues(false, true);
        verify(authApi, never()).googleNonce();
        retryClock.advanceTimeBy(1L, TimeUnit.SECONDS);
        assertHealthRequest(server.takeRequest(2, TimeUnit.SECONDS));
        nonceResult.awaitDone(5L, TimeUnit.SECONDS)
                .assertValue(value -> value instanceof com.nexachat.core.common.Result.Success<?>);
        verify(authApi, times(1)).googleNonce();

        var exchangeStarting = gate.getIsStarting().test();
        var exchangeResult = repository.googleSignIn("google-id-token", "nonce").test();
        assertHealthRequest(server.takeRequest(2, TimeUnit.SECONDS));
        exchangeStarting.awaitCount(2).assertValues(false, true);
        verify(authApi, never()).googleSignIn(any());
        retryClock.advanceTimeBy(1L, TimeUnit.SECONDS);
        assertHealthRequest(server.takeRequest(2, TimeUnit.SECONDS));
        exchangeResult.awaitDone(5L, TimeUnit.SECONDS)
                .assertValue(value -> value instanceof com.nexachat.core.common.Result.Success<?>);
        verify(authApi, times(1)).googleSignIn(any());
    }

    private ServerWakeGate gate(TestScheduler retryClock) {
        OkHttpClient healthClient = new OkHttpClient.Builder()
                .connectTimeout(1L, TimeUnit.SECONDS)
                .readTimeout(200L, TimeUnit.MILLISECONDS)
                .build();
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(server.url("/"))
                .client(healthClient)
                .build();
        HealthApi healthApi = retrofit.create(HealthApi.class);
        return new ServerWakeGate(healthApi, Schedulers.trampoline(), retryClock,
                () -> retryClock.now(TimeUnit.MILLISECONDS));
    }

    private static TokenStore refreshStore() {
        TokenStore store = mock(TokenStore.class);
        when(store.readTokens()).thenReturn(Maybe.just(new Tokens("a0", Long.MAX_VALUE, "r0")));
        when(store.readSessionSnapshot()).thenReturn(Single.just(
                new SessionSnapshot(1L, SessionSnapshot.Lifecycle.ACTIVE)));
        when(store.readPendingRefresh()).thenReturn(Maybe.empty());
        when(store.reservePendingRefresh(anyLong(), anyString(), anyString()))
                .thenReturn(Single.just(true));
        when(store.commitRefreshRotation(anyLong(), anyString(), anyString(), anyLong(), anyString(),
                any(RecallWindowConfig.class))).thenReturn(Completable.complete());
        return store;
    }

    private static MockResponse response(int status) {
        return new MockResponse().setResponseCode(status).setBody("{\"status\":\"UP\"}");
    }

    private static void assertHealthRequest(RecordedRequest request) {
        assertTrue("health retry should dispatch a request", request != null);
        assertEquals("GET", request.getMethod());
        assertEquals("/actuator/health", request.getPath());
    }
}
