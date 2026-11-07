package com.nexachat.core.network.stomp;

import com.nexachat.core.storage.TokenStore;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;

/**
 * Guards the WS endpoint derivation: the request URL must stay a legal OkHttp
 * http(s) URL (ws/wss schemes throw IllegalArgumentException in HttpUrl — this
 * regression crashed the app at DI construction).
 */
public final class WsEndpointUrlTest {

    private final TokenStore tokenStore = mock(TokenStore.class);

    @Test
    public void appendsWsPathAndKeepsHttpScheme() {
        HttpUrl url = WsEndpoint.fromBaseUrl("http://10.0.2.2:8080");
        assertEquals("http", url.scheme());
        assertEquals("http://10.0.2.2:8080/ws", url.toString());
    }

    @Test
    public void keepsHttpsSchemeWhichImpliesWssDuringUpgrade() {
        HttpUrl url = WsEndpoint.fromBaseUrl("https://chat.example.com");
        assertEquals("https", url.scheme());
        assertEquals("https://chat.example.com/ws", url.toString());
    }

    @Test
    public void resultIsAcceptedByRequestBuilderWithoutThrowing() {
        // This is the exact call path that crashed with "unexpected scheme: ws".
        Request request = new Request.Builder()
                .url(WsEndpoint.fromBaseUrl("http://192.168.1.10:8080"))
                .build();
        assertEquals("/ws", request.url().encodedPath());
    }

    @Test
    public void preservesNestedBasePaths() {
        HttpUrl url = WsEndpoint.fromBaseUrl("http://example.com/api/v1");
        assertEquals("http://example.com/api/v1/ws", url.toString());
    }

    @Test
    public void stompClientConstructsWithoutThrowingDiInitCrashRegression() {
        // The wsUrl field initializer runs in the constructor; the ws-scheme
        // bug threw IllegalArgumentException here and killed the app at
        // startup. Constructing the real client with inert deps covers it.
        StompClient client = new StompClient(new OkHttpClient(), tokenStore);
        assertNotNull(client);
    }
}
