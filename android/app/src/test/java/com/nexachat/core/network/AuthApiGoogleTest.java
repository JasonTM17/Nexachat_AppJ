package com.nexachat.core.network;

import com.nexachat.core.network.dto.GoogleLinkRequest;
import com.nexachat.core.network.dto.GoogleNonceResponse;
import com.nexachat.core.network.dto.GoogleSignInRequest;
import com.nexachat.core.network.dto.GoogleUnlinkRequest;
import com.nexachat.core.network.dto.LinkedIdentitiesResponse;
import com.squareup.moshi.Moshi;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import retrofit2.Retrofit;
import retrofit2.converter.moshi.MoshiConverterFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Wire contract for the Google auth endpoints. The server is a MockWebServer:
 * this asserts the method, path, body and JSON shape Retrofit produces (and
 * that the two public paths are declared public for the auth interceptor).
 */
public final class AuthApiGoogleTest {

    private MockWebServer server;
    private AuthApi api;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        Moshi moshi = new Moshi.Builder().build();
        api = new Retrofit.Builder()
                .baseUrl(server.url("/"))
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(AuthApi.class);
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void nonceIsAPublicPostWithNoBody() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"nonce\":\"n-1\",\"expiresInSeconds\":300}"));

        GoogleNonceResponse response = api.googleNonce().execute().body();

        assertEquals("n-1", response.getNonce());
        assertEquals(300, response.getExpiresInSeconds());
        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/api/v1/auth/google/nonce", request.getPath());
        assertEquals(0, request.getBodySize());
        assertTrue(AuthApi.isPublicAuthPath(request.getPath()));
    }

    @Test
    public void signInPostsTheIdTokenAndNonce() throws Exception {
        server.enqueue(new MockResponse().setBody(authResponseJson()));

        var response = api.googleSignIn(new GoogleSignInRequest("id-token", "nonce-1")).execute();

        assertTrue(response.isSuccessful());
        assertEquals("u-1", response.body().getUser().getId());
        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/api/v1/auth/google", request.getPath());
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"idToken\":\"id-token\""));
        assertTrue(body.contains("\"nonce\":\"nonce-1\""));
        assertTrue(AuthApi.isPublicAuthPath(request.getPath()));
    }

    @Test
    public void linkPostsTokenNonceAndPasswordAndIsNotPublic() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        var response = api.linkGoogle(
                new GoogleLinkRequest("id-token", "nonce-1", "secret123")).execute();

        assertTrue(response.isSuccessful());
        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertEquals("/api/v1/auth/identities/google", request.getPath());
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("\"password\":\"secret123\""));
        assertFalse(AuthApi.isPublicAuthPath(request.getPath()));
    }

    @Test
    public void unlinkSendsDeleteWithThePassword() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        var response = api.unlinkGoogle(new GoogleUnlinkRequest("secret123")).execute();

        assertTrue(response.isSuccessful());
        RecordedRequest request = server.takeRequest();
        assertEquals("DELETE", request.getMethod());
        assertEquals("/api/v1/auth/identities/google", request.getPath());
        assertTrue(request.getBody().readUtf8().contains("\"password\":\"secret123\""));
        assertFalse(AuthApi.isPublicAuthPath(request.getPath()));
    }

    @Test
    public void linkedIdentitiesParsesTheEnvelope() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "{\"identities\":[{\"provider\":\"google\",\"email\":\"user@example.com\","
                        + "\"linkedAt\":\"2026-01-01T00:00:00Z\"}]}"));

        LinkedIdentitiesResponse response = api.linkedIdentities().execute().body();

        assertEquals(1, response.getIdentities().size());
        assertEquals("google", response.getIdentities().get(0).getProvider());
        assertEquals("user@example.com", response.getIdentities().get(0).getEmail());
        RecordedRequest request = server.takeRequest();
        assertEquals("GET", request.getMethod());
        assertEquals("/api/v1/auth/identities", request.getPath());
        assertFalse(AuthApi.isPublicAuthPath(request.getPath()));
    }

    private static String authResponseJson() {
        return "{"
                + "\"user\":{\"id\":\"u-1\",\"phone\":\"+84912345678\",\"displayName\":\"Minh\"},"
                + "\"accessToken\":\"access\",\"accessTokenExpiresIn\":900,"
                + "\"refreshToken\":\"refresh\",\"recallWindowSeconds\":86400,"
                + "\"groupAdminMayRecallAfterWindow\":true"
                + "}";
    }
}
