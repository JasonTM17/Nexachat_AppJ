package com.nexachat.core.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.nullable;

import com.nexachat.core.network.dto.DeleteAccountRequest;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import retrofit2.Retrofit;
import retrofit2.converter.moshi.MoshiConverterFactory;

public final class TokenAuthenticatorWrongPasswordTest {

    private static final String ACCESS = "access-token";

    private MockWebServer server;
    private AuthApi authApi;
    private final AtomicInteger refreshAttempts = new AtomicInteger();

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        authApi = mock(AuthApi.class);
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    public void deleteAccountWrongPasswordPerformsSingleRequestWithoutRefresh() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(401)
                .setBody("{\"error\":{\"code\":\"WRONG_PASSWORD\"}}")
                .addHeader("Content-Type", "application/json"));

        UserApi userApi = userApi(server);

        IOException failure = null;
        try {
            userApi.deleteMe(new DeleteAccountRequest("bad-pass")).execute();
        } catch (IOException error) {
            failure = error;
        }

        assertEquals(1, server.getRequestCount());
        RecordedRequest request = server.takeRequest();
        assertEquals("DELETE", request.getMethod());
        assertEquals("/api/v1/users/me", request.getPath());
        verify(authApi, never()).refresh(anyString(), any());
        assertEquals(0, refreshAttempts.get());
        assertNull(failure);
    }

    @Test
    public void changePasswordWrongPasswordPerformsSingleRequestWithoutRefresh() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(401)
                .setBody("{\"error\":{\"code\":\"WRONG_PASSWORD\"}}")
                .addHeader("Content-Type", "application/json"));

        AuthApi liveApi = liveAuthApi(server);

        IOException failure = null;
        try {
            liveApi.changePassword(new com.nexachat.core.network.dto.ChangePasswordRequest(
                    "old", "new", "keep-refresh")).execute();
        } catch (IOException error) {
            failure = error;
        }

        assertEquals(1, server.getRequestCount());
        RecordedRequest request = server.takeRequest();
        assertEquals("PATCH", request.getMethod());
        assertEquals("/api/v1/auth/password", request.getPath());
        verify(authApi, never()).refresh(anyString(), any());
        assertEquals(0, refreshAttempts.get());
        assertNull(failure);
    }

    private UserApi userApi(MockWebServer webServer) {
        Moshi moshi = new Moshi.Builder().build();
        OkHttpClient client = client(webServer);
        return new Retrofit.Builder()
                .baseUrl(webServer.url("/"))
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(UserApi.class);
    }

    private AuthApi liveAuthApi(MockWebServer webServer) {
        Moshi moshi = new Moshi.Builder().build();
        return new Retrofit.Builder()
                .baseUrl(webServer.url("/"))
                .client(client(webServer))
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(AuthApi.class);
    }

    private OkHttpClient client(MockWebServer webServer) {
        TokenRefresher refresher = mock(TokenRefresher.class);
        when(refresher.refreshIfNeededBlocking(nullable(String.class))).thenAnswer(invocation -> {
            refreshAttempts.incrementAndGet();
            throw new AssertionError("refresh must not run for WRONG_PASSWORD");
        });
        Interceptor auth = chain -> chain.proceed(chain.request().newBuilder()
                .header(AuthInterceptor.HEADER_AUTHORIZATION, AuthInterceptor.BEARER_PREFIX + ACCESS)
                .build());
        return new OkHttpClient.Builder()
                .addInterceptor(auth)
                .authenticator(new TokenAuthenticator(refresher))
                .build();
    }

}
