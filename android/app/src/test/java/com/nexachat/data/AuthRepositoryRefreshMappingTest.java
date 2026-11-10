package com.nexachat.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import static org.mockito.Mockito.mock;

import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.TokenRefreshOutcome;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.notification.DeviceTokenRegistrationManager;
import org.junit.Test;

public final class AuthRepositoryRefreshMappingTest {

    @Test
    public void recoveryRequiredMapsToSessionExpired() {
        AuthRepository repository = new AuthRepository(
                mock(com.nexachat.core.network.AuthApi.class),
                mock(TokenStore.class),
                mock(com.nexachat.core.network.SessionEvents.class),
                mock(TokenRefresher.class),
                mock(DeviceTokenRegistrationManager.class));
        Result<Boolean> result = repository.refreshMapping(
                TokenRefreshOutcome.RecoveryRequired.INSTANCE);
        assertTrue(result instanceof Result.Failure<?>);
        assertEquals(
                ApiError.SessionExpired.INSTANCE,
                ((Result.Failure<?>) result).getError());
    }

    @Test
    public void transientFailureStillMapsToNetwork() {
        AuthRepository repository = new AuthRepository(
                mock(com.nexachat.core.network.AuthApi.class),
                mock(TokenStore.class),
                mock(com.nexachat.core.network.SessionEvents.class),
                mock(TokenRefresher.class),
                mock(DeviceTokenRegistrationManager.class));
        Result<Boolean> result = repository.refreshMapping(
                TokenRefreshOutcome.TransientFailure.INSTANCE);
        assertTrue(result instanceof Result.Failure<?>);
        assertTrue(((Result.Failure<?>) result).getError() instanceof ApiError.Network);
    }
}
