package com.nexachat.core.crypto;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.crypto.DeviceKeyStore.Identity;
import com.nexachat.core.network.E2eeApi;
import com.nexachat.core.network.dto.E2eeDeviceDtos.ConversationE2eeDto;
import com.nexachat.core.network.dto.E2eeDeviceDtos.DeviceDto;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Maybe;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.ResponseBody;
import org.junit.Before;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;

/**
 * Device-session behavior: registration on session availability, the
 * conflict-regeneration rule, device-list caching, and the no-identity decrypt
 * path. Network is mocked at the Retrofit {@link Call} boundary.
 */
public final class E2eeSessionTest {

    private E2eeApi api;
    private DeviceKeyStore keyStore;
    private TokenStore tokenStore;
    private E2eeSession session;

    @Before
    public void setUp() {
        api = mock(E2eeApi.class);
        keyStore = mock(DeviceKeyStore.class);
        tokenStore = mock(TokenStore.class);
        session = new E2eeSession(api, keyStore, tokenStore, new Moshi.Builder().build());
    }

    @Test
    public void registersStoredIdentityOnSessionAvailable() throws Exception {
        Identity identity = new Identity("dev-1", new byte[32], new byte[32]);
        when(keyStore.identity("u1")).thenReturn(identity);
        when(tokenStore.readTokens()).thenReturn(Maybe.just(tokensFor("u1")));
        Call<DeviceDto> call = mock(Call.class);
        when(call.execute()).thenReturn(Response.success(new DeviceDto()));
        when(api.registerDevice(anyString(), any())).thenReturn(call);

        session.onSessionAvailable().test().awaitDone(5, TimeUnit.SECONDS).assertComplete();

        verify(keyStore).rememberCurrentUser("u1");
        verify(api).registerDevice(org.mockito.ArgumentMatchers.eq("dev-1"), any());
    }

    @Test
    public void conflictRegeneratesIdentityOnce() throws Exception {
        Identity stored = new Identity("dev-old", new byte[32], new byte[32]);
        when(tokenStore.readTokens()).thenReturn(Maybe.just(tokensFor("u1")));
        // After clear() the next lookup misses, so ensureIdentity mints a fresh
        // pair under the mocked dev-new id.
        when(keyStore.identity("u1")).thenReturn(stored, (Identity) null);
        when(keyStore.deviceId("u1")).thenReturn("dev-new");
        Call<DeviceDto> conflict = mock(Call.class);
        when(conflict.execute()).thenReturn(Response.error(409, ResponseBody.create(
                MediaType.get("application/json"),
                "{\"error\":{\"code\":\"E2EE_DEVICE_CONFLICT\"}}")));
        Call<DeviceDto> ok = mock(Call.class);
        when(ok.execute()).thenReturn(Response.success(new DeviceDto()));
        when(api.registerDevice(org.mockito.ArgumentMatchers.eq("dev-old"), any())).thenReturn(conflict);
        when(api.registerDevice(org.mockito.ArgumentMatchers.eq("dev-new"), any())).thenReturn(ok);

        session.onSessionAvailable().test().awaitDone(5, TimeUnit.SECONDS).assertComplete();

        verify(keyStore).clear("u1");
        verify(api).registerDevice(org.mockito.ArgumentMatchers.eq("dev-new"), any());
    }

    @Test
    public void e2eeStateCachesUntilInvalidated() throws Exception {
        ConversationE2eeDto dto = new ConversationE2eeDto(true, java.util.List.of());
        Call<ConversationE2eeDto> call = mock(Call.class);
        when(call.execute()).thenReturn(Response.success(dto));
        when(call.clone()).thenReturn(call);
        when(api.getE2ee("c1")).thenReturn(call);

        session.e2eeState("c1").test().awaitDone(5, TimeUnit.SECONDS).assertValue(dto);
        session.e2eeState("c1").test().awaitDone(5, TimeUnit.SECONDS).assertValue(dto);
        verify(api, org.mockito.Mockito.times(1)).getE2ee("c1");

        session.invalidateDevices("c1");
        session.e2eeState("c1").test().awaitDone(5, TimeUnit.SECONDS).assertValue(dto);
        verify(api, org.mockito.Mockito.times(2)).getE2ee("c1");
    }

    @Test
    public void decryptNowWithoutIdentityReturnsEmpty() {
        when(keyStore.currentUserId()).thenReturn("u1");
        when(keyStore.identity("u1")).thenReturn(null);

        assertTrue(session.decryptNow("c1", "u2", "m1", "e2ee1:abc", java.util.List.of()).isEmpty());
    }

    @Test
    public void decryptNowWithIdentityAndUnknownEnvelopeReturnsEmpty()
            throws java.security.GeneralSecurityException {
        byte[] priv = E2eeCrypto.generateDeviceKeyPair().privateKey();
        Identity identity = new Identity("dev-1",
                E2eeCrypto.publicFromPrivate(priv), priv);
        when(keyStore.currentUserId()).thenReturn("u1");
        when(keyStore.identity("u1")).thenReturn(identity);

        assertFalse(session.decryptNow("c1", "u2", "m1", "e2ee1:abc",
                java.util.List.of()).isPresent());
    }

    @Test
    public void toggleSurfacesServerErrorsAsE2eeException() throws Exception {
        Call<ConversationE2eeDto> call = mock(Call.class);
        when(call.execute()).thenReturn(Response.error(409, ResponseBody.create(
                MediaType.get("application/json"),
                "{\"error\":{\"code\":\"E2EE_UNSUPPORTED_CONVERSATION\"}}")));
        when(api.setE2ee(org.mockito.ArgumentMatchers.eq("c9"), any())).thenReturn(call);

        session.setE2ee("c9", true).test().awaitDone(5, TimeUnit.SECONDS)
                .assertError(error -> error instanceof E2eeSession.E2eeException
                        && "E2EE_UNSUPPORTED_CONVERSATION".equals(error.getMessage()));
    }

    @Test
    public void revokeIgnoresNetworkFailureButStillWipes() throws Exception {
        Identity identity = new Identity("dev-1", new byte[32], new byte[32]);
        when(keyStore.identity("u1")).thenReturn(identity);
        Call<Void> call = mock(Call.class);
        when(call.execute()).thenThrow(new IOException("offline"));
        when(api.revokeDevice("dev-1")).thenReturn(call);

        session.revokeCurrentDevice("u1").test().awaitDone(5, TimeUnit.SECONDS).assertComplete();

        verify(keyStore).clear("u1");
        verify(keyStore).forgetCurrentUser();
    }

    private static Tokens tokensFor(String userId) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + userId + "\"}").getBytes(StandardCharsets.UTF_8));
        return new Tokens("header." + payload + ".sig", 0L, "refresh");
    }
}
