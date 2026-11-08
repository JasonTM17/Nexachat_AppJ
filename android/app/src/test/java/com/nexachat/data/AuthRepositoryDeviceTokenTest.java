package com.nexachat.data;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.common.Result;
import com.nexachat.core.network.AuthApi;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.network.dto.AuthResponse;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.notification.DeviceTokenRegistrationManager;
import io.reactivex.rxjava3.core.Completable;
import org.junit.Test;
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
        when(tokenStore.saveTokens(anyString(), anyLong(), anyString()))
                .thenReturn(Completable.complete());
        when(tokenManager.onSessionAvailable()).thenReturn(Completable.complete());

        AuthRepository repository = new AuthRepository(
                authApi, tokenStore, sessionEvents, tokenRefresher, tokenManager);

        Result<AuthResponse> result = repository.login("+84901234567", "password123")
                .blockingGet();

        assertTrue(result instanceof Result.Success<?>);
        verify(tokenStore).saveTokens(anyString(), anyLong(), anyString());
        verify(tokenManager).onSessionAvailable();
    }
}
