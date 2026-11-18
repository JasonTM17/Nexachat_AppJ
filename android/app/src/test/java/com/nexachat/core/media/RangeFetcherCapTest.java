package com.nexachat.core.media;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Test;

/** Cap enforcement inside {@link VideoThumbnailLoader.RangeFetcher}. */
public final class RangeFetcherCapTest {

    @Test
    public void headDeclaringOversizeIsRejectedBeforeAnyRead() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Length", "999"));
            server.start();
            VideoThumbnailLoader.RangeFetcher fetcher = new VideoThumbnailLoader.RangeFetcher(
                    new OkHttpClient(), server.url("/v.mp4").toString(), 10);
            try {
                fetcher.getSize();
                fail("expected oversize rejection");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("too large"));
            }
        }
    }

    @Test
    public void readAbortsOnceTheCumulativeBudgetIsSpent() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            // HEAD probe: declared size fits the cap exactly.
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Length", "8"));
            // GET: server answers 206 but streams MORE than the range asked —
            // the counted stream must abort past the remaining budget.
            server.enqueue(new MockResponse().setResponseCode(206).setBody("0123456789abcdef"));
            server.start();
            VideoThumbnailLoader.RangeFetcher fetcher = new VideoThumbnailLoader.RangeFetcher(
                    new OkHttpClient(), server.url("/v.mp4").toString(), 8);
            try {
                fetcher.readAt(0, new byte[16], 0, 16);
                fail("expected budget abort");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("exceeded"));
            }
        }
    }
}
