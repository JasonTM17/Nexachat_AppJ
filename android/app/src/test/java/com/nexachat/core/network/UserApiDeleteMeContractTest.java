package com.nexachat.core.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.nexachat.core.network.dto.DeleteAccountRequest;
import com.squareup.moshi.Moshi;
import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.moshi.MoshiConverterFactory;

public final class UserApiDeleteMeContractTest {

    private MockWebServer server;
    private UserApi userApi;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        Moshi moshi = new Moshi.Builder().build();
        userApi = new Retrofit.Builder()
                .baseUrl(server.url("/"))
                .client(new okhttp3.OkHttpClient())
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(UserApi.class);
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    public void deleteMeProxyIssuesDeleteWithJsonBody() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(204));

        Response<Void> response =
                userApi.deleteMe(new DeleteAccountRequest("current-pass")).execute();

        assertEquals(204, response.code());
        assertEquals(1, server.getRequestCount());
        RecordedRequest request = server.takeRequest();
        assertEquals("DELETE", request.getMethod());
        assertEquals("/api/v1/users/me", request.getPath());
        assertEquals("{\"password\":\"current-pass\"}", request.getBody().readUtf8());
    }

    @Test
    public void legacyDeleteAnnotationCannotCarryBody() {
        interface BadDeleteApi {
            @retrofit2.http.DELETE("api/v1/users/me")
            retrofit2.Call<Void> deleteMe(@retrofit2.http.Body DeleteAccountRequest body);
        }
        BadDeleteApi badApi = new Retrofit.Builder()
                .baseUrl(server.url("/"))
                .client(new okhttp3.OkHttpClient())
                .addConverterFactory(MoshiConverterFactory.create(new Moshi.Builder().build()))
                .build()
                .create(BadDeleteApi.class);

        assertThrows(IllegalArgumentException.class, () ->
                badApi.deleteMe(new DeleteAccountRequest("secret")).execute());
    }
}
