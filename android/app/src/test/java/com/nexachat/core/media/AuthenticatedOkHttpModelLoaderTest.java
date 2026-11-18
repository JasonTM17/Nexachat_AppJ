package com.nexachat.core.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import com.bumptech.glide.Priority;
import com.bumptech.glide.load.data.DataFetcher;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Test;

public final class AuthenticatedOkHttpModelLoaderTest {

    @Test
    public void authenticatedLoaderOnlyHandlesTheConfiguredApiOrigin() {
        AuthenticatedOkHttpModelLoader loader = new AuthenticatedOkHttpModelLoader(
                mock(OkHttpClient.class), HttpUrl.get("https://chat.test:8443"));

        assertTrue(loader.handles("https://chat.test:8443/api/v1/attachments/a/content"));
        assertFalse(loader.handles("https://cdn.test:8443/avatar.png"));
        assertFalse(loader.handles("https://chat.test/avatar.png"));
        assertFalse(loader.handles("http://chat.test:8443/api/v1/attachments/a/content"));
        assertFalse(loader.handles("file:///private/avatar.png"));
        assertFalse(loader.handles("not a URL"));
    }

    @Test
    public void declaredOversizeBodyIsRejectedBeforeStreaming() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setBody("x".repeat(64))
                    .setHeader("Content-Length", "64"));
            server.start();
            AuthenticatedOkHttpModelLoader loader = new AuthenticatedOkHttpModelLoader(
                    new OkHttpClient(), server.url("/"), 16);
            String model = server.url("/api/v1/attachments/a/content").toString();
            CapturingCallback callback = new CapturingCallback();
            loader.buildLoadData(model, 0, 0, new com.bumptech.glide.load.Options()).fetcher.loadData(Priority.IMMEDIATE, callback);
            assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
            assertNotNull("oversize body must fail the load", callback.error);
            assertNull(callback.stream);
        }
    }

    @Test
    public void bodyWithinTheCapStreamsThrough() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("hello"));
            server.start();
            AuthenticatedOkHttpModelLoader loader = new AuthenticatedOkHttpModelLoader(
                    new OkHttpClient(), server.url("/"), 16);
            String model = server.url("/api/v1/attachments/a/content").toString();
            CapturingCallback callback = new CapturingCallback();
            loader.buildLoadData(model, 0, 0, new com.bumptech.glide.load.Options()).fetcher.loadData(Priority.IMMEDIATE, callback);
            assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
            assertNull(callback.error);
            assertNotNull(callback.stream);
            assertEquals("hello", new String(callback.stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            callback.stream.close();
        }
    }

    private static final class CapturingCallback implements DataFetcher.DataCallback<InputStream> {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile InputStream stream;
        volatile Exception error;

        @Override
        public void onDataReady(InputStream data) {
            stream = data;
            latch.countDown();
        }

        @Override
        public void onLoadFailed(Exception e) {
            error = e;
            latch.countDown();
        }
    }
}
