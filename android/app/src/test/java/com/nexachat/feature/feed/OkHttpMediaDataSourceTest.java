package com.nexachat.feature.feed;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.DataSpec;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public final class OkHttpMediaDataSourceTest {

    private MockWebServer server;
    private OkHttpClient client;
    private String baseUrl;

    @Before
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        baseUrl = server.url("/").toString();
        client = new OkHttpClient.Builder().build();
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void sendsRangeHeaderAndReadsHttp206PartialContent() throws Exception {
        byte[] slice = "56789".getBytes(StandardCharsets.US_ASCII);
        server.enqueue(new MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes 5-9/20")
                .setBody(new Buffer().write(slice)));

        DataSource source = new OkHttpMediaDataSource.Factory(client, baseUrl).createDataSource();
        DataSpec spec = new DataSpec.Builder()
                .setUri(mockUri(baseUrl + "api/v1/feed/videos/v1"))
                .setPosition(5L)
                .setLength(5L)
                .build();

        long opened = source.open(spec);
        assertEquals(5L, opened);

        byte[] read = readAll(source);
        source.close();

        assertArrayEquals(slice, read);
        RecordedRequest recorded = server.takeRequest();
        assertEquals("bytes=5-9", recorded.getHeader("Range"));
    }

    @Test
    public void http416ThrowsOutOfRangeDataSourceException() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(416));

        DataSource source = new OkHttpMediaDataSource.Factory(client, baseUrl).createDataSource();
        DataSpec spec = new DataSpec.Builder()
                .setUri(mockUri(baseUrl + "api/v1/feed/videos/v1"))
                .setPosition(999L)
                .build();

        try {
            source.open(spec);
            fail("Expected DataSourceException for HTTP 416");
        } catch (DataSourceException expected) {
            assertEquals(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, expected.reason);
        } finally {
            source.close();
        }
    }

    @Test
    public void http200WithNonZeroPositionSkipsPrefixBytesAndReadsRemaining() throws Exception {
        byte[] fullPayload = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(new Buffer().write(fullPayload)));

        DataSource source = new OkHttpMediaDataSource.Factory(client, baseUrl).createDataSource();
        DataSpec spec = new DataSpec.Builder()
                .setUri(mockUri(baseUrl + "api/v1/feed/videos/v1"))
                .setPosition(6L)
                .setLength(4L)
                .build();

        long opened = source.open(spec);
        assertEquals(4L, opened);

        byte[] read = readAll(source);
        source.close();

        assertArrayEquals("6789".getBytes(StandardCharsets.US_ASCII), read);
        RecordedRequest recorded = server.takeRequest();
        assertEquals("bytes=6-9", recorded.getHeader("Range"));
    }

    @Test
    public void http200WithPositionBeyondContentLengthThrowsOutOfRange() throws Exception {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("short"));

        DataSource source = new OkHttpMediaDataSource.Factory(client, baseUrl).createDataSource();
        DataSpec spec = new DataSpec.Builder()
                .setUri(mockUri(baseUrl + "api/v1/feed/videos/v1"))
                .setPosition(20L)
                .build();

        try {
            source.open(spec);
            fail("Expected DataSourceException when HTTP 200 body is shorter than position");
        } catch (DataSourceException expected) {
            assertEquals(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, expected.reason);
        } finally {
            source.close();
        }
    }

    @Test
    public void prematureEofBeforeContentLengthThrowsDataSourceExceptionWithEofCause() throws Exception {
        // Chunked response ends cleanly after 5 bytes while DataSpec requests 100 bytes.
        server.enqueue(new MockResponse()
                .setResponseCode(206)
                .setChunkedBody("hello", 5));

        DataSource source = new OkHttpMediaDataSource.Factory(client, baseUrl).createDataSource();
        DataSpec spec = new DataSpec.Builder()
                .setUri(mockUri(baseUrl + "api/v1/feed/videos/v1"))
                .setPosition(0L)
                .setLength(100L)
                .build();

        long opened = source.open(spec);
        assertEquals(100L, opened);

        byte[] buf = new byte[128];
        int total = 0;
        try {
            while (true) {
                int n = source.read(buf, total, buf.length - total);
                if (n == C.RESULT_END_OF_INPUT) {
                    fail("Expected EOF exception instead of RESULT_END_OF_INPUT");
                }
                total += n;
            }
        } catch (DataSourceException expected) {
            assertEquals(5, total);
            assertTrue("Expected EOFException cause, got " + expected.getCause(),
                    expected.getCause() instanceof EOFException);
        } finally {
            source.close();
        }
    }

    @Test
    public void crossOriginUrlIsRejected() throws Exception {
        DataSource source = new OkHttpMediaDataSource.Factory(client, baseUrl).createDataSource();
        DataSpec spec = new DataSpec.Builder()
                .setUri(mockUri("https://evil.example.test/video.mp4"))
                .build();

        try {
            source.open(spec);
            fail("Expected DataSourceException for off-origin URL");
        } catch (DataSourceException expected) {
            assertEquals(PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED, expected.reason);
        } finally {
            source.close();
        }
        assertEquals(0, server.getRequestCount());
    }

    @Test
    public void closeCancelsInFlightCall() throws Exception {
        CountDownLatch requestArrived = new CountDownLatch(1);
        CountDownLatch releaseServer = new CountDownLatch(1);
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                requestArrived.countDown();
                try {
                    releaseServer.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                return new MockResponse().setResponseCode(200).setBody("late");
            }
        });

        DataSource source = new OkHttpMediaDataSource.Factory(client, baseUrl).createDataSource();
        DataSpec spec = new DataSpec.Builder()
                .setUri(mockUri(baseUrl + "api/v1/feed/videos/v1"))
                .build();

        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch openFinished = new CountDownLatch(1);
        Thread opener = new Thread(() -> {
            try {
                source.open(spec);
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                openFinished.countDown();
            }
        });
        opener.start();

        assertTrue(requestArrived.await(3, TimeUnit.SECONDS));
        source.close();
        assertTrue(openFinished.await(3, TimeUnit.SECONDS));
        releaseServer.countDown();

        assertTrue("Expected in-flight call to fail upon close()", failure.get() != null);
    }

    private static Uri mockUri(String raw) {
        Uri uri = Mockito.mock(Uri.class);
        Mockito.when(uri.toString()).thenReturn(raw);
        return uri;
    }

    private static byte[] readAll(DataSource source) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16];
        while (true) {
            int n = source.read(buf, 0, buf.length);
            if (n == C.RESULT_END_OF_INPUT) {
                break;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
