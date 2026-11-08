package com.nexachat.notification;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.network.DeviceTokenApi;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Maybe;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import retrofit2.Call;
import retrofit2.Response;

public final class DeviceTokenSyncExecutorTest {
    private static final String DEVICE_TOKEN = "device-token-fixture";

    private TokenStore tokenStore;
    private DeviceTokenApi deviceTokenApi;
    private FirebaseTokenProvider firebaseTokenProvider;
    private DeviceTokenSyncExecutor executor;

    @Before
    public void setUp() {
        tokenStore = mock(TokenStore.class);
        deviceTokenApi = mock(DeviceTokenApi.class);
        firebaseTokenProvider = mock(FirebaseTokenProvider.class);
        executor = new DeviceTokenSyncExecutor(
                tokenStore, deviceTokenApi, firebaseTokenProvider, new Moshi.Builder().build());
        when(tokenStore.readPendingDeviceTokenUnregister()).thenReturn(Maybe.empty());
    }

    @Test
    public void doesNothingWhenThereIsNoSession() throws Exception {
        when(tokenStore.readTokens()).thenReturn(Maybe.empty());

        assertEquals(DeviceTokenSyncExecutor.Outcome.SUCCESS, executor.execute(0));

        verify(deviceTokenApi, never()).register(any());
        verify(deviceTokenApi, never()).unregister(any(), any());
        verify(firebaseTokenProvider, never()).getTokenIfConfigured();
    }

    @Test
    public void registersStoredTokenForCurrentSession() throws Exception {
        when(tokenStore.readTokens()).thenReturn(Maybe.just(tokens("user-1")));
        when(tokenStore.readFcmDeviceToken()).thenReturn(Maybe.just(DEVICE_TOKEN));
        Call<DeviceTokenApi.RegistrationResponse> call = mock(Call.class);
        when(deviceTokenApi.register(any())).thenReturn(call);
        when(call.execute()).thenReturn(Response.success(new DeviceTokenApi.RegistrationResponse()));

        assertEquals(DeviceTokenSyncExecutor.Outcome.SUCCESS, executor.execute(0));

        ArgumentCaptor<DeviceTokenApi.RegistrationRequest> requestCaptor =
                ArgumentCaptor.forClass(DeviceTokenApi.RegistrationRequest.class);
        verify(deviceTokenApi).register(requestCaptor.capture());
        assertEquals(DEVICE_TOKEN, requestCaptor.getValue().getToken());
        assertEquals("ANDROID", requestCaptor.getValue().getPlatform());
        verify(firebaseTokenProvider, never()).getTokenIfConfigured();
    }

    @Test
    public void missingFirebaseConfigurationDegradesWithoutARegistrationRequest() throws Exception {
        when(tokenStore.readTokens()).thenReturn(Maybe.just(tokens("user-1")));
        when(tokenStore.readFcmDeviceToken()).thenReturn(Maybe.empty());
        when(firebaseTokenProvider.getTokenIfConfigured()).thenReturn(null);

        assertEquals(DeviceTokenSyncExecutor.Outcome.SUCCESS, executor.execute(0));

        verify(deviceTokenApi, never()).register(any());
    }

    @Test
    public void failedRegistrationUsesBoundedRetryDecision() throws IOException {
        when(tokenStore.readTokens()).thenReturn(Maybe.just(tokens("user-1")));
        when(tokenStore.readFcmDeviceToken()).thenReturn(Maybe.just(DEVICE_TOKEN));
        Call<DeviceTokenApi.RegistrationResponse> call = mock(Call.class);
        when(deviceTokenApi.register(any())).thenReturn(call);
        when(call.execute()).thenReturn(Response.error(503,
                okhttp3.ResponseBody.create(null, new byte[0])));

        assertEquals(DeviceTokenSyncExecutor.Outcome.RETRY, executor.execute(0));
        assertEquals(DeviceTokenSyncExecutor.Outcome.FAILURE,
                executor.execute(DeviceTokenRetryPolicy.MAX_RETRIES));
    }

    private static Tokens tokens(String userId) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + userId + "\"}").getBytes(StandardCharsets.UTF_8));
        return new Tokens("header." + payload + ".signature", Long.MAX_VALUE, "refresh-fixture");
    }
}
