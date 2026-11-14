package com.nexachat.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.network.dto.LoginRequest;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.notification.DeviceTokenRegistrationManager;
import com.squareup.moshi.Moshi;
import org.junit.Test;

public final class AuthRepositoryLoginRequestTest {

    private static AuthRepository repository() {
        return new AuthRepository(
                mock(com.nexachat.core.network.AuthApi.class),
                mock(TokenStore.class),
                mock(com.nexachat.core.network.SessionEvents.class),
                mock(TokenRefresher.class),
                mock(DeviceTokenRegistrationManager.class));
    }

    @Test
    public void emailSignsInThroughTheIdentifierFieldLowerCased() throws Exception {
        LoginRequest request = repository().loginRequest("  Jason@Gmail.com ", "secret123");
        assertEquals("jason@gmail.com", request.getIdentifier());
        String json = new Moshi.Builder().build().adapter(LoginRequest.class).toJson(request);
        assertTrue(json, json.contains("\"identifier\":\"jason@gmail.com\""));
        assertFalse(json, json.contains("\"phone\""));
    }

    @Test
    public void phoneKeepsTheLegacyPhoneField() throws Exception {
        LoginRequest request = repository().loginRequest("+84 901 234 567", "secret123");
        assertEquals("+84901234567", request.getPhone());
        assertNull(request.getIdentifier());
        String json = new Moshi.Builder().build().adapter(LoginRequest.class).toJson(request);
        assertFalse(json, json.contains("identifier"));
    }
}