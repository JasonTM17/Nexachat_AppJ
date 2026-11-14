package com.nexachat.core.network;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class AiRequestTimeoutTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void onlySummaryAndAskPostsGetTheModelChainReadBudget() throws IOException {
        Context context = mock(Context.class);
        when(context.getCacheDir()).thenReturn(temporaryFolder.newFolder());
        AuthInterceptor auth = mock(AuthInterceptor.class);
        when(auth.intercept(any())).thenAnswer(invocation -> {
            Interceptor.Chain chain = invocation.getArgument(0);
            return chain.proceed(chain.request());
        });
        OkHttpClient base = NetworkModule.provideOkHttpClient(context, auth, mock(TokenAuthenticator.class));
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            OkHttpClient client = base.newBuilder().addInterceptor(chain -> {
                Response response = chain.proceed(chain.request());
                return response.newBuilder()
                        .header("Test-Read-Timeout", Integer.toString(chain.readTimeoutMillis()))
                        .build();
            }).build();
            String conversation = "/api/v1/conversations/7b0c1f5e-1111-2222-3333-444455556666";
            assertReadTimeout(server, client, conversation + "/summary", true, 130);
            assertReadTimeout(server, client, conversation + "/ask", true, 130);
            assertReadTimeout(server, client, conversation + "/summary", false, 15);
            assertReadTimeout(server, client, conversation + "/messages", true, 15);
            assertReadTimeout(server, client, conversation + "/nested/ask", true, 15);
        } finally {
            if (base.cache() != null) {
                base.cache().close();
            }
        }
    }

    private static void assertReadTimeout(MockWebServer server, OkHttpClient client,
            String path, boolean post, int seconds) throws IOException {
        server.enqueue(new MockResponse().setBody("{}"));
        Request.Builder request = new Request.Builder().url(server.url(path));
        if (post) {
            request.post(RequestBody.create(new byte[0]));
        }
        try (Response response = client.newCall(request.build()).execute()) {
            assertEquals(200, response.code());
            assertEquals(path, Long.toString(TimeUnit.SECONDS.toMillis(seconds)),
                    response.header("Test-Read-Timeout"));
        }
    }
}
