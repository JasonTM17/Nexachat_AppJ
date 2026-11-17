package com.nexachat.core.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.nexachat.core.network.dto.LinkPhoneRequest;
import com.nexachat.core.network.dto.UserDto;
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

public final class UserApiPhoneContractTest {

    private static final String PROFILE_JSON =
            "{\"id\":\"u1\",\"phone\":\"+84901234567\",\"displayName\":\"A\"}";

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
    public void linkPhoneIssuesPutWithJsonBodyAndParsesProfile() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(PROFILE_JSON));

        Response<UserDto> response =
                userApi.linkPhone(new LinkPhoneRequest("+84901234567")).execute();

        assertEquals(200, response.code());
        RecordedRequest request = server.takeRequest();
        assertEquals("PUT", request.getMethod());
        assertEquals("/api/v1/users/me/phone", request.getPath());
        assertEquals("{\"phone\":\"+84901234567\"}", request.getBody().readUtf8());
        assertEquals("+84901234567", response.body().getPhone());
    }

    @Test
    public void removePhoneIssuesDeleteAndParsesProfile() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"id\":\"u1\",\"displayName\":\"A\"}"));

        Response<UserDto> response = userApi.removePhone().execute();

        assertEquals(200, response.code());
        RecordedRequest request = server.takeRequest();
        assertEquals("DELETE", request.getMethod());
        assertEquals("/api/v1/users/me/phone", request.getPath());
        assertNull(response.body().getPhone());
    }
}
