package com.nexachat.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.common.Result;
import com.nexachat.core.database.AppDatabase;
import com.nexachat.core.network.AuthApi;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.network.UserApi;
import com.nexachat.core.network.dto.DeleteAccountRequest;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.feature.auth.LoginNotice;
import com.nexachat.notification.DeviceTokenRegistrationManager;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import retrofit2.Call;
import retrofit2.Response;

public final class AuthRepositoryDeleteAccountTest {

    @Test
    public void deleteAccountCallsApiThenClearsSessionLikeLogout() throws Exception {
        AuthApi authApi = mock(AuthApi.class);
        UserApi userApi = mock(UserApi.class);
        TokenStore tokenStore = mock(TokenStore.class);
        SessionEvents sessionEvents = mock(SessionEvents.class);
        TokenRefresher tokenRefresher = mock(TokenRefresher.class);
        DeviceTokenRegistrationManager tokenManager = mock(DeviceTokenRegistrationManager.class);
        AppDatabase database = mock(AppDatabase.class);
        LoginNotice loginNotice = new LoginNotice();
        AccountDataCleaner cleaner = new AccountDataCleaner(
                database, new SessionOperationTracker(), Completable::complete);

        Call<Void> call = mock(Call.class);
        when(userApi.deleteMe(any())).thenReturn(call);
        when(call.execute()).thenReturn(Response.success(204, null));
        when(tokenManager.onLogout()).thenReturn(Completable.complete());
        when(tokenStore.clear()).thenReturn(Completable.complete());
        when(tokenStore.readPendingDeviceTokenUnregister()).thenReturn(Maybe.empty());

        AuthRepository repository = new AuthRepository(
                authApi, userApi, tokenStore, sessionEvents, tokenRefresher,
                tokenManager, cleaner, loginNotice);

        Result<com.nexachat.core.common.Unit> result =
                repository.deleteAccount("current-pass").blockingGet();

        assertTrue(result instanceof Result.Success<?>);
        ArgumentCaptor<DeleteAccountRequest> body = ArgumentCaptor.forClass(DeleteAccountRequest.class);
        verify(userApi).deleteMe(body.capture());
        assertEquals("current-pass", body.getValue().getPassword());
        verify(tokenRefresher).onLogout();
        verify(tokenStore).clear();
        verify(sessionEvents).emitLoggedOut();
        assertEquals(Integer.valueOf(com.nexachat.R.string.login_account_deleted_notice),
                loginNotice.consume());

        InOrder order = inOrder(database, sessionEvents);
        order.verify(database).clearAllTables();
        order.verify(sessionEvents).emitLoggedOut();
    }

    @Test
    public void localCleanupFailureAfterServerDeleteStillSucceeds() throws Exception {
        AuthApi authApi = mock(AuthApi.class);
        UserApi userApi = mock(UserApi.class);
        TokenStore tokenStore = mock(TokenStore.class);
        SessionEvents sessionEvents = mock(SessionEvents.class);
        TokenRefresher tokenRefresher = mock(TokenRefresher.class);
        DeviceTokenRegistrationManager tokenManager = mock(DeviceTokenRegistrationManager.class);
        AppDatabase database = mock(AppDatabase.class);
        LoginNotice loginNotice = new LoginNotice();
        AccountDataCleaner cleaner = new AccountDataCleaner(
                database, new SessionOperationTracker(), Completable::complete);

        Call<Void> call = mock(Call.class);
        when(userApi.deleteMe(any())).thenReturn(call);
        when(call.execute()).thenReturn(Response.success(204, null));
        when(tokenManager.onLogout()).thenReturn(Completable.complete());
        when(tokenStore.clear()).thenReturn(Completable.complete());
        when(tokenStore.readPendingDeviceTokenUnregister()).thenReturn(Maybe.empty());
        doThrow(new RuntimeException("room down")).when(database).clearAllTables();

        AuthRepository repository = new AuthRepository(
                authApi, userApi, tokenStore, sessionEvents, tokenRefresher,
                tokenManager, cleaner, loginNotice);

        Result<com.nexachat.core.common.Unit> result =
                repository.deleteAccount("current-pass").blockingGet();

        assertTrue(result instanceof Result.Success<?>);
        verify(tokenStore).clear();
        verify(sessionEvents).emitLoggedOut();
        assertEquals(Integer.valueOf(com.nexachat.R.string.login_account_deleted_notice),
                loginNotice.consume());
    }

    @Test
    public void wrongPasswordLeavesSessionIntact() throws Exception {
        AuthApi authApi = mock(AuthApi.class);
        UserApi userApi = mock(UserApi.class);
        TokenStore tokenStore = mock(TokenStore.class);
        SessionEvents sessionEvents = mock(SessionEvents.class);
        TokenRefresher tokenRefresher = mock(TokenRefresher.class);
        DeviceTokenRegistrationManager tokenManager = mock(DeviceTokenRegistrationManager.class);
        AppDatabase database = mock(AppDatabase.class);
        LoginNotice loginNotice = new LoginNotice();
        AccountDataCleaner cleaner = new AccountDataCleaner(
                database, new SessionOperationTracker(), Completable::complete);

        Call<Void> call = mock(Call.class);
        when(userApi.deleteMe(any())).thenReturn(call);
        when(call.execute()).thenReturn(Response.error(401,
                okhttp3.ResponseBody.create(
                        "{\"error\":{\"code\":\"WRONG_PASSWORD\"}}",
                        okhttp3.MediaType.get("application/json"))));

        AuthRepository repository = new AuthRepository(
                authApi, userApi, tokenStore, sessionEvents, tokenRefresher,
                tokenManager, cleaner, loginNotice);

        Result<com.nexachat.core.common.Unit> result =
                repository.deleteAccount("wrong").blockingGet();

        assertTrue(result instanceof Result.Failure<?>);
        verify(tokenRefresher, never()).onLogout();
        verify(tokenStore, never()).clear();
        verify(sessionEvents, never()).emitLoggedOut();
        assertNull(loginNotice.consume());
    }
}
