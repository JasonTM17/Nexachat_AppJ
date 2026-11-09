package com.nexachat.data;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

import com.nexachat.core.common.Result;
import com.nexachat.core.network.AuthApi;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.network.TokenRefreshOutcome;
import com.nexachat.core.network.dto.AuthResponse;
import com.nexachat.core.network.dto.LogoutRequest;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.RecallWindowConfig;
import com.nexachat.core.storage.Tokens;
import com.nexachat.core.database.AppDatabase;
import com.nexachat.core.database.OutboxDao;
import com.squareup.moshi.Moshi;
import com.nexachat.notification.DeviceTokenRegistrationManager;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import java.util.concurrent.TimeUnit;
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
        order.verify(database).clearAccountDataPreservingOutbox();
        order.verify(sessionEvents).emitLoggedOut();
    }

    @Test
    public void startupRestoreBindsLegacyQueueBeforeRefreshCanRejectStoredSession() {
        AuthApi authApi = mock(AuthApi.class);
        TokenStore tokenStore = mock(TokenStore.class);
        SessionEvents sessionEvents = mock(SessionEvents.class);
        TokenRefresher tokenRefresher = mock(TokenRefresher.class);
        DeviceTokenRegistrationManager tokenManager = mock(DeviceTokenRegistrationManager.class);
        AppDatabase database = mock(AppDatabase.class);
        OutboxDao outboxDao = mock(OutboxDao.class);
        when(database.outboxDao()).thenReturn(outboxDao);
        when(outboxDao.assignLegacyOwner("user-a")).thenReturn(Completable.complete());
        when(tokenStore.readTokens()).thenReturn(Maybe.just(
                new Tokens(jwtFor("user-a"), 1L, "refresh-a")));
        when(tokenRefresher.refresh()).thenReturn(
                io.reactivex.rxjava3.core.Single.just(TokenRefreshOutcome.Rejected.INSTANCE));
        AccountDataCleaner cleaner = new AccountDataCleaner(
                database, new SessionOperationTracker(), Completable::complete);
        AuthRepository repository = new AuthRepository(authApi, tokenStore, sessionEvents,
                tokenRefresher, tokenManager, cleaner, new Moshi.Builder().build());

        Result<Boolean> result = repository.refresh().blockingGet();

        assertTrue(result instanceof Result.Failure<?>);
        InOrder order = inOrder(outboxDao, tokenRefresher);
        order.verify(outboxDao).assignLegacyOwner("user-a");
        order.verify(tokenRefresher).refresh();
        verify(tokenManager, never()).onSessionAvailable();
    }

    @Test
    public void startupWithoutStoredSessionSealsLegacyRowsBeforeRefresh() {
        AuthApi authApi = mock(AuthApi.class);
        TokenStore tokenStore = mock(TokenStore.class);
        SessionEvents sessionEvents = mock(SessionEvents.class);
        TokenRefresher tokenRefresher = mock(TokenRefresher.class);
        DeviceTokenRegistrationManager tokenManager = mock(DeviceTokenRegistrationManager.class);
        AppDatabase database = mock(AppDatabase.class);
        OutboxDao outboxDao = mock(OutboxDao.class);
        when(database.outboxDao()).thenReturn(outboxDao);
        when(outboxDao.sealUnassignedLegacyOwners()).thenReturn(Completable.complete());
        when(tokenStore.readTokens()).thenReturn(Maybe.empty());
        when(tokenRefresher.refresh()).thenReturn(io.reactivex.rxjava3.core.Single.just(
                TokenRefreshOutcome.NoRefreshToken.INSTANCE));
        AccountDataCleaner cleaner = new AccountDataCleaner(
                database, new SessionOperationTracker(), Completable::complete);
        AuthRepository repository = new AuthRepository(authApi, tokenStore, sessionEvents,
                tokenRefresher, tokenManager, cleaner, new Moshi.Builder().build());

        repository.refresh().blockingGet();

        InOrder order = inOrder(outboxDao, tokenRefresher);
        order.verify(outboxDao).sealUnassignedLegacyOwners();
        order.verify(tokenRefresher).refresh();
        verify(outboxDao, never()).assignLegacyOwner(anyString());
    }

    @Test
    public void explicitNewLoginDoesNotClaimOwnerlessUpgradeRows() throws Exception {
        AuthApi authApi = mock(AuthApi.class);
        TokenStore tokenStore = mock(TokenStore.class);
        SessionEvents sessionEvents = mock(SessionEvents.class);
        TokenRefresher tokenRefresher = mock(TokenRefresher.class);
        DeviceTokenRegistrationManager tokenManager = mock(DeviceTokenRegistrationManager.class);
        AppDatabase database = mock(AppDatabase.class);
        OutboxDao outboxDao = mock(OutboxDao.class);
        when(database.outboxDao()).thenReturn(outboxDao);
        when(outboxDao.sealUnassignedLegacyOwners()).thenReturn(Completable.complete());
        when(tokenStore.readTokens()).thenReturn(Maybe.empty());
        AuthResponse response = new AuthResponse(
                new UserDto("user-b", "+84901234567", "Bea"),
                "access-b", 3600L, "refresh-b");
        Call<AuthResponse> call = mock(Call.class);
        when(authApi.login(any())).thenReturn(call);
        when(call.execute()).thenReturn(Response.success(response));
        when(tokenStore.saveSession(anyString(), anyLong(), anyString(), any(RecallWindowConfig.class)))
                .thenReturn(Completable.complete());
        when(tokenManager.onSessionAvailable()).thenReturn(Completable.complete());
        AccountDataCleaner cleaner = new AccountDataCleaner(
                database, new SessionOperationTracker(), Completable::complete);
        AuthRepository repository = new AuthRepository(authApi, tokenStore, sessionEvents,
                tokenRefresher, tokenManager, cleaner, new Moshi.Builder().build());

        Result<AuthResponse> result = repository.login("+84901234567", "password123").blockingGet();

        assertTrue(result instanceof Result.Success<?>);
        verify(database).clearAccountDataPreservingOutbox();
        InOrder order = inOrder(outboxDao, tokenStore);
        order.verify(outboxDao, org.mockito.Mockito.times(2)).sealUnassignedLegacyOwners();
        order.verify(tokenStore).saveSession(anyString(), anyLong(), anyString(),
                any(RecallWindowConfig.class));
        verify(outboxDao, never()).assignLegacyOwner(anyString());
    }

    @Test
    public void explicitNewLoginBindsLegacyRowsToStoredOwnerBeforeReplacingSession() throws Exception {
        AuthApi authApi = mock(AuthApi.class);
        TokenStore tokenStore = mock(TokenStore.class);
        SessionEvents sessionEvents = mock(SessionEvents.class);
        TokenRefresher tokenRefresher = mock(TokenRefresher.class);
        DeviceTokenRegistrationManager tokenManager = mock(DeviceTokenRegistrationManager.class);
        AppDatabase database = mock(AppDatabase.class);
        OutboxDao outboxDao = mock(OutboxDao.class);
        when(database.outboxDao()).thenReturn(outboxDao);
        when(outboxDao.assignLegacyOwner("user-a")).thenReturn(Completable.complete());
        when(outboxDao.sealUnassignedLegacyOwners()).thenReturn(Completable.complete());
        when(tokenStore.readTokens()).thenReturn(Maybe.just(
                new Tokens(jwtFor("user-a"), 1L, "refresh-a")));
        AuthResponse response = new AuthResponse(
                new UserDto("user-b", "+84907654321", "Bo"),
                "access-b", 3600L, "refresh-b");
        Call<AuthResponse> call = mock(Call.class);
        when(authApi.login(any())).thenReturn(call);
        when(call.execute()).thenReturn(Response.success(response));
        when(tokenStore.saveSession(anyString(), anyLong(), anyString(), any(RecallWindowConfig.class)))
                .thenReturn(Completable.complete());
        when(tokenManager.onSessionAvailable()).thenReturn(Completable.complete());
        AccountDataCleaner cleaner = new AccountDataCleaner(
                database, new SessionOperationTracker(), Completable::complete);
        AuthRepository repository = new AuthRepository(authApi, tokenStore, sessionEvents,
                tokenRefresher, tokenManager, cleaner, new Moshi.Builder().build());

        Result<AuthResponse> result = repository.login("+84907654321", "placeholder-password")
                .blockingGet();

        assertTrue(result instanceof Result.Success<?>);
        InOrder order = inOrder(outboxDao, tokenStore);
        order.verify(outboxDao).assignLegacyOwner("user-a");
        order.verify(outboxDao).sealUnassignedLegacyOwners();
        order.verify(tokenStore).saveSession(anyString(), anyLong(), anyString(),
                any(RecallWindowConfig.class));
        verify(outboxDao, never()).assignLegacyOwner("user-b");
    }

    @Test
    public void transientLegacyOwnerAssignmentFailureDoesNotSealRowsAndCanRetry() {
        AuthApi authApi = mock(AuthApi.class);
        TokenStore tokenStore = mock(TokenStore.class);
        SessionEvents sessionEvents = mock(SessionEvents.class);
        TokenRefresher tokenRefresher = mock(TokenRefresher.class);
        DeviceTokenRegistrationManager tokenManager = mock(DeviceTokenRegistrationManager.class);
        AppDatabase database = mock(AppDatabase.class);
        OutboxDao outboxDao = mock(OutboxDao.class);
        when(database.outboxDao()).thenReturn(outboxDao);
        when(outboxDao.sealUnassignedLegacyOwners()).thenReturn(Completable.complete());
        when(outboxDao.assignLegacyOwner("user-a")).thenReturn(
                Completable.error(new IllegalStateException("temporary storage failure")),
                Completable.complete());
        when(tokenStore.readTokens()).thenReturn(Maybe.just(
                new Tokens(jwtFor("user-a"), 1L, "refresh-a")));
        when(tokenRefresher.refresh()).thenReturn(io.reactivex.rxjava3.core.Single.just(
                new TokenRefreshOutcome.FreshToken("fresh-access")));
        when(tokenManager.onSessionAvailable()).thenReturn(Completable.complete());
        AccountDataCleaner cleaner = new AccountDataCleaner(
                database, new SessionOperationTracker(), Completable::complete);
        AuthRepository repository = new AuthRepository(authApi, tokenStore, sessionEvents,
                tokenRefresher, tokenManager, cleaner, new Moshi.Builder().build());

        repository.refresh().test().awaitDone(5, TimeUnit.SECONDS)
                .assertError(IllegalStateException.class);
        verify(outboxDao, never()).sealUnassignedLegacyOwners();
        verify(tokenRefresher, never()).refresh();

        Result<Boolean> retry = repository.refresh().blockingGet();

        assertTrue(retry instanceof Result.Success<?>);
        verify(outboxDao, org.mockito.Mockito.times(2)).assignLegacyOwner("user-a");
        verify(outboxDao, never()).sealUnassignedLegacyOwners();
        verify(tokenRefresher).refresh();
    }

    private static String jwtFor(String userId) {
        String payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + userId + "\"}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "header." + payload + ".signature";
    }
}
