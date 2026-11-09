package com.nexachat.data;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

import com.nexachat.core.common.Result;
import com.nexachat.core.network.AuthApi;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.network.dto.AuthResponse;
import com.nexachat.core.network.dto.LogoutRequest;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.RecallWindowConfig;
import com.nexachat.core.storage.Tokens;
import com.nexachat.notification.DeviceTokenRegistrationManager;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import retrofit2.Call;
import retrofit2.Response;

public final class AuthRepositoryDeviceTokenTest {

    @Test
    public void loginStoresSessionBeforeSchedulingDeviceTokenSync() throws Exception {
        AuthApi authApi = mock(AuthApi.class);
        TokenStore tokenStore = mock(TokenStore.class);
        SessionEvents sessionEvents = mock(SessionEvents.class);
        TokenRefresher tokenRefresher = mock(TokenRefresher.class);
        DeviceTokenRegistrationManager tokenManager = mock(DeviceTokenRegistrationManager.class);

        AuthResponse response = new AuthResponse(
                new UserDto("user-1", "+84901234567", "Son"),
                "access-token-fixture", 3600L, "refresh-token-fixture");
        Call<AuthResponse> call = mock(Call.class);
        when(authApi.login(any())).thenReturn(call);
        when(call.execute()).thenReturn(Response.success(response));
        when(tokenStore.saveSession(anyString(), anyLong(), anyString(), any(RecallWindowConfig.class)))
                .thenReturn(Completable.complete());
        when(tokenManager.onSessionAvailable()).thenReturn(Completable.complete());

        AuthRepository repository = new AuthRepository(
                authApi, tokenStore, sessionEvents, tokenRefresher, tokenManager);

        Result<AuthResponse> result = repository.login("+84901234567", "password123")
                .blockingGet();

        assertTrue(result instanceof Result.Success<?>);
        verify(tokenStore).saveSession(anyString(), anyLong(), anyString(), any(RecallWindowConfig.class));
        verify(tokenManager).onSessionAvailable();
    }

    @Test
    public void logoutRevokesRefreshTokenThenClearsLocalSession() throws Exception {
        AuthApi authApi = mock(AuthApi.class);
        TokenStore tokenStore = mock(TokenStore.class);
        SessionEvents sessionEvents = mock(SessionEvents.class);
        TokenRefresher tokenRefresher = mock(TokenRefresher.class);
        DeviceTokenRegistrationManager tokenManager = mock(DeviceTokenRegistrationManager.class);

        Call<Void> call = mock(Call.class);
        when(tokenStore.readTokens()).thenReturn(Maybe.just(
                new Tokens("access-fixture", 1L, "refresh-fixture")));
        when(authApi.logout(any())).thenReturn(call);
        when(call.execute()).thenReturn(Response.success(null));
        when(tokenManager.onLogout()).thenReturn(Completable.complete());
        when(tokenStore.clear()).thenReturn(Completable.complete());

        AuthRepository repository = new AuthRepository(
                authApi, tokenStore, sessionEvents, tokenRefresher, tokenManager);
        Result<com.nexachat.core.common.Unit> result = repository.logout().blockingGet();

        Assert.assertTrue(result instanceof Result.Success<?>);
        ArgumentCaptor<LogoutRequest> request = ArgumentCaptor.forClass(LogoutRequest.class);
        verify(authApi).logout(request.capture());
        Assert.assertEquals("refresh-fixture", request.getValue().getRefreshToken());
        verify(tokenStore).clear();
        verify(sessionEvents).emitLoggedOut();
    }

    @Test
    public void logoutWaitsForFullRoomCleanupBeforePublishingLoggedOut() throws Exception {
        AuthApi authApi = mock(AuthApi.class);
        TokenStore tokenStore = mock(TokenStore.class);
        SessionEvents sessionEvents = mock(SessionEvents.class);
        TokenRefresher tokenRefresher = mock(TokenRefresher.class);
        DeviceTokenRegistrationManager tokenManager = mock(DeviceTokenRegistrationManager.class);
        com.nexachat.core.database.AppDatabase database =
                mock(com.nexachat.core.database.AppDatabase.class);
        AccountDataCleaner cleaner = new AccountDataCleaner(
                database, new SessionOperationTracker(), Completable::complete);

        Call<Void> call = mock(Call.class);
        when(tokenStore.readTokens()).thenReturn(Maybe.just(
                new Tokens("access-fixture", 1L, "refresh-fixture")));
        when(authApi.logout(any())).thenReturn(call);
        when(call.execute()).thenReturn(Response.success(null));
        when(tokenManager.onLogout()).thenReturn(Completable.complete());
        when(tokenStore.clear()).thenReturn(Completable.complete());

        AuthRepository repository = new AuthRepository(
                authApi, tokenStore, sessionEvents, tokenRefresher, tokenManager, cleaner);
        repository.logout().blockingGet();

        InOrder order = inOrder(database, sessionEvents);
        order.verify(database).clearAllTables();
        order.verify(sessionEvents).emitLoggedOut();
    }
}
