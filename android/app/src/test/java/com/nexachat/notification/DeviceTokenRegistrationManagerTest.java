package com.nexachat.notification;

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.network.DeviceTokenApi;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public final class DeviceTokenRegistrationManagerTest {
    private static final String DEVICE_TOKEN = "device-token-fixture";
    private static final String ACCESS_TOKEN = jwt("user-1");

    private TokenStore tokenStore;
    private DeviceTokenApi deviceTokenApi;
    private DeviceTokenWorkScheduler scheduler;
    private DeviceTokenRegistrationManager manager;

    @Before
    public void setUp() {
        tokenStore = mock(TokenStore.class);
        deviceTokenApi = mock(DeviceTokenApi.class);
        scheduler = mock(DeviceTokenWorkScheduler.class);
        manager = new DeviceTokenRegistrationManager(
                tokenStore, deviceTokenApi, scheduler, new Moshi.Builder().build());
        when(tokenStore.saveFcmDeviceToken(anyString())).thenReturn(Completable.complete());
    }

    @Test
    public void newTokenIsStoredWithoutSchedulingWhenThereIsNoSession() {
        when(tokenStore.readTokens()).thenReturn(Maybe.empty());

        manager.onNewToken(DEVICE_TOKEN).test().assertComplete();

        verify(tokenStore).saveFcmDeviceToken(DEVICE_TOKEN);
        verify(scheduler, never()).enqueueSync();
    }

    @Test
    public void newTokenIsStoredAndScheduledWhenSessionExists() {
        when(tokenStore.readTokens()).thenReturn(Maybe.just(tokens()));

        manager.onNewToken(DEVICE_TOKEN).test().assertComplete();

        verify(tokenStore).saveFcmDeviceToken(DEVICE_TOKEN);
        verify(scheduler).enqueueSync();
    }

    @Test
    public void sessionAvailableEnqueuesSync() {
        manager.onSessionAvailable().test().assertComplete();

        verify(scheduler).enqueueSync();
    }

    @Test
    public void logoutQueuesUnregisterAndReturnsBeforeItsCallback() {
        Call<Void> call = mock(Call.class);
        when(tokenStore.readTokens()).thenReturn(Maybe.just(tokens()));
        when(tokenStore.readFcmDeviceToken()).thenReturn(Maybe.just(DEVICE_TOKEN));
        when(tokenStore.savePendingDeviceTokenUnregister(DEVICE_TOKEN, "user-1"))
                .thenReturn(Completable.complete());
        when(deviceTokenApi.unregister(any(), anyString())).thenReturn(call);

        manager.onLogout().test().assertComplete();

        verify(tokenStore).savePendingDeviceTokenUnregister(DEVICE_TOKEN, "user-1");
        ArgumentCaptor<Callback<Void>> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
        verify(call).enqueue(callbackCaptor.capture());
        verify(scheduler, never()).enqueueSync();

        callbackCaptor.getValue().onFailure(call, new IOException("offline"));

        verify(scheduler).enqueueSync();
    }

    @Test
    public void successfulLogoutUnregisterClearsOnlyItsPendingRequest() {
        Call<Void> call = mock(Call.class);
        when(tokenStore.readTokens()).thenReturn(Maybe.just(tokens()));
        when(tokenStore.readFcmDeviceToken()).thenReturn(Maybe.just(DEVICE_TOKEN));
        when(tokenStore.savePendingDeviceTokenUnregister(DEVICE_TOKEN, "user-1"))
                .thenReturn(Completable.complete());
        when(tokenStore.clearPendingDeviceTokenUnregister(DEVICE_TOKEN, "user-1"))
                .thenReturn(Completable.complete());
        when(deviceTokenApi.unregister(any(), anyString())).thenReturn(call);

        manager.onLogout().test().assertComplete();

        ArgumentCaptor<Callback<Void>> callbackCaptor = ArgumentCaptor.forClass(Callback.class);
        verify(call).enqueue(callbackCaptor.capture());
        callbackCaptor.getValue().onResponse(call, Response.success(204, null));

        verify(tokenStore).clearPendingDeviceTokenUnregister(DEVICE_TOKEN, "user-1");
        verify(scheduler, never()).enqueueSync();
    }

    @Test
    public void newTokenIsNotWrittenToStandardStreams() {
        when(tokenStore.readTokens()).thenReturn(Maybe.empty());

        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out));
            System.setErr(new PrintStream(err));
            manager.onNewToken(DEVICE_TOKEN).test().assertComplete();
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        assertFalse(out.toString().contains(DEVICE_TOKEN));
        assertFalse(err.toString().contains(DEVICE_TOKEN));
    }

    private static Tokens tokens() {
        return new Tokens(ACCESS_TOKEN, Long.MAX_VALUE, "refresh-fixture");
    }

    private static String jwt(String userId) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + userId + "\"}").getBytes(StandardCharsets.UTF_8));
        return "header." + payload + ".signature";
    }
}
