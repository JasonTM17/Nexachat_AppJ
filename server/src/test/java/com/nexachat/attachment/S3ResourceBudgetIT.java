package com.nexachat.attachment;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Acceptance coverage for the remote-file serving budget.
 *
 * <p>A local path-style S3 surface records every upstream request: the
 * {@code Range} header it carried, its status, and how many body bytes the
 * server attempted to write. Each test asserts that serving a short range or
 * a trailing sniff window consumes only the bounded windows the controller
 * asks for, never the whole object, and that authorization still runs before
 * any byte is requested.</p>
 *
 * <p>The same fixture injects response-header delays and failed range reads
 * to prove the configured SDK deadlines and error-path recovery. It is a
 * loopback fixture only; no real object-store provider is contacted.</p>
 */
class S3ResourceBudgetIT {

    private static final String BUCKET = "nexa-storage-budget";
    private static final int SNIFF_WINDOW = 64 * 1024;
    private static final int MIB = 1024 * 1024;
    private static final byte[] PNG_MAGIC = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
    };

    @TempDir
    Path tempDir;

    private FakeS3 fixture;
    private S3Client client;
    private S3StorageService storage;
    private AttachmentService attachmentService;
    private FileController controller;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new FakeS3();
        S3StorageProperties properties = new S3StorageProperties(
                fixture.endpoint().toString(), "us-east-1", BUCKET, "budget-key", "budget-secret",
                null, null, null, null);
        client = new S3StorageConfiguration().s3StorageClient(properties);
        storage = new S3StorageService(client, properties);
        attachmentService = Mockito.mock(AttachmentService.class);
        controller = new FileController(storage, attachmentService);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (fixture != null) {
            fixture.close();
        }
    }

    // ------------------------------------------------------ byte budgets

    @Test
    void lastKilobyteOfALargeObjectUsesOneBoundedPrefixAndOneExactSlice() throws Exception {
        byte[] large = png(25 * MIB);
        String key = "attachments/large.png";
        fixture.put(key, large);

        Captured response = serve(key, "bytes=-1024");

        assertThat(response.status()).isEqualTo(206);
        assertThat(response.header("Content-Range"))
                .isEqualTo("bytes " + (large.length - 1024) + "-" + (large.length - 1)
                        + "/" + large.length);
        assertThat(response.header("Content-Length")).isEqualTo("1024");
        assertThat(response.body()).containsExactly(
                Arrays.copyOfRange(large, large.length - 1024, large.length));
        assertThat(response.header("Content-Type")).isEqualTo("image/png");

        assertThat(fixture.calls()).hasSize(2);
        assertThat(fixture.calls().get(0).range()).isEqualTo("bytes=0-" + (SNIFF_WINDOW - 1));
        assertThat(fixture.calls().get(1).range())
                .isEqualTo("bytes=" + (large.length - 1024) + "-" + (large.length - 1));
        assertThat(fixture.calls()).allSatisfy(call -> assertThat(call.range()).isNotNull());
        assertThat(fixture.upstreamBytes()).isLessThanOrEqualTo(SNIFF_WINDOW + 1024L);
        assertThat(fixture.upstreamBytes()).isLessThan(large.length / 100L);
    }

    @Test
    void bmffTailSniffAddsOnlyOneBoundedTailWindow() throws Exception {
        byte[] voice = isoBmff(8 * MIB, "soun");
        String key = "attachments/voice.m4a";
        fixture.put(key, voice);
        long size = voice.length;
        long suffixStart = size - SNIFF_WINDOW;
        long bodyStart = size - 1024;

        Captured response = serve(key, "bytes=-1024");

        assertThat(response.status()).isEqualTo(206);
        assertThat(response.header("Content-Type")).isEqualTo("audio/mp4");
        assertThat(response.body()).containsExactly(
                Arrays.copyOfRange(voice, (int) bodyStart, voice.length));

        assertThat(fixture.calls()).hasSize(3);
        assertThat(fixture.calls()).extracting(FakeS3.Call::range).containsExactly(
                "bytes=0-" + (SNIFF_WINDOW - 1),
                "bytes=" + suffixStart + "-" + (size - 1),
                "bytes=" + bodyStart + "-" + (size - 1));
        assertThat(fixture.upstreamBytes())
                .isLessThanOrEqualTo((long) SNIFF_WINDOW + SNIFF_WINDOW + 1024);
        assertThat(fixture.upstreamBytes()).isLessThan(size);
    }

    @Test
    void fullDownloadStreamsTheBodyAndSniffsTheTailWithOneBoundedRange() throws Exception {
        byte[] voice = isoBmff(8 * MIB, "soun");
        String key = "attachments/voice.m4a";
        fixture.put(key, voice);
        long size = voice.length;

        Captured response = serve(key, null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.header("Content-Type")).isEqualTo("audio/mp4");
        assertThat(response.body()).containsExactly(voice);
        assertThat(fixture.calls()).hasSize(2);
        assertThat(fixture.calls().get(0).range()).isNull();
        assertThat(fixture.calls().get(1).range())
                .isEqualTo("bytes=" + (size - SNIFF_WINDOW) + "-" + (size - 1));
        assertThat(fixture.upstreamBytes())
                .isLessThanOrEqualTo(size + SNIFF_WINDOW)
                .isLessThan(size * 2);
    }

    @Test
    void unsatisfiableAndMalformedRangesReadOnlyTheBoundedPrefix() throws Exception {
        byte[] large = png(25 * MIB);
        String key = "attachments/large.png";
        fixture.put(key, large);

        for (String bad : new String[] {"bytes=999999999-", "bytes=5-3", "bytes=abc-def"}) {
            fixture.reset();
            Captured response = serve(key, bad);

            assertThat(response.status()).as("range %s", bad).isEqualTo(416);
            assertThat(response.header("Content-Range"))
                    .isEqualTo("bytes */" + large.length);
            assertThat(response.body()).isNull();
            assertThat(fixture.calls()).as("range %s", bad).hasSize(1);
            assertThat(fixture.calls().get(0).range()).isEqualTo("bytes=0-" + (SNIFF_WINDOW - 1));
            assertThat(fixture.upstreamBytes())
                    .as("range %s", bad)
                    .isLessThanOrEqualTo(SNIFF_WINDOW);
        }
    }

    // ------------------------------------------------------ authorization

    @Test
    void deniedMemberReadsNoUpstreamBytesForFullOrRangeRequests() {
        String key = "attachments/denied.png";
        fixture.put(key, png(4096));
        Mockito.doThrow(ApiException.of(ApiErrorCode.NOT_MEMBER))
                .when(attachmentService)
                .assertStorageKeyReadable(key, UUID.fromString(
                        "00000000-0000-0000-0000-000000000001"));

        JwtPrincipal principal = new JwtPrincipal(UUID.fromString(
                "00000000-0000-0000-0000-000000000001"));
        for (String range : new String[] {null, "bytes=-1"}) {
            ApiException ex = catchThrowableOfType(ApiException.class,
                    () -> controller.serve(request(key, range), principal));
            assertThat(ex.getCode()).isEqualTo(ApiErrorCode.NOT_MEMBER);
        }

        assertThat(fixture.calls()).isEmpty();
        assertThat(fixture.upstreamBytes()).isZero();
    }

    // ------------------------------------------------- local/S3 parity

    @Test
    void localAndRemoteBackendsReturnIdenticalBytesAndHeaders() throws Exception {
        byte[] content = png(4096);
        LocalStorageService local = new LocalStorageService(tempDir.toString());
        StorageService.StoredFile localFile =
                local.store("attachments", "x.png", "image/png", content);
        // Uploads are exercised against the S3 mock in S3StorageServiceIT; this
        // fixture seeds the object map directly so the read budget is isolated.
        String remoteKey = "attachments/parity.png";
        fixture.put(remoteKey, content);
        FileController localController = new FileController(local, attachmentService);

        for (String range : new String[] {null, "bytes=0-99", "bytes=100-", "bytes=-50",
                "bytes=4096-"}) {
            Captured localResponse = serve(localController, localFile.storageKey(), range);
            Captured remoteResponse = serve(controller, remoteKey, range);

            assertThat(remoteResponse.status()).as("range %s", range)
                    .isEqualTo(localResponse.status());
            assertThat(remoteResponse.header("Content-Type")).as("range %s", range)
                    .isEqualTo(localResponse.header("Content-Type"));
            assertThat(remoteResponse.header("Content-Range")).as("range %s", range)
                    .isEqualTo(localResponse.header("Content-Range"));
            assertThat(remoteResponse.header("Content-Length")).as("range %s", range)
                    .isEqualTo(localResponse.header("Content-Length"));
            assertThat(remoteResponse.body()).as("range %s", range)
                    .isEqualTo(localResponse.body());
        }
    }

    // ---------------------------------------------- deadlines and recovery

    @Test
    void stalledProviderFailsWithinTheConfiguredDeadlineAndRecovers() {
        String key = "attachments/slow.png";
        fixture.put(key, png(4096));
        S3StorageProperties quick = new S3StorageProperties(
                fixture.endpoint().toString(), "us-east-1", BUCKET, "budget-key", "budget-secret",
                Duration.ofSeconds(2), Duration.ofMillis(700),
                Duration.ofMillis(500), Duration.ofMillis(300));
        S3StorageService quickStorage = new S3StorageService(
                new S3StorageConfiguration().s3StorageClient(quick), quick);
        fixture.delayGetHeaders(Duration.ofSeconds(5));

        long started = System.nanoTime();
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> quickStorage.readPrefix(key, 64));
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.INTERNAL_ERROR);
        assertThat(elapsedMillis).isLessThan(3000L);
        assertThat(fixture.getAttempts()).isBetween(1, 3);

        fixture.delayGetHeaders(Duration.ZERO);
        StorageService.PrefixRead prefix = quickStorage.readPrefix(key, 64);
        assertThat(prefix.sizeBytes()).isEqualTo(4096);
        assertThat(prefix.bytes()).hasSize(64);
    }

    @Test
    void failedTailReadLeavesTheHandlerReusable() throws Exception {
        byte[] voice = isoBmff(8 * MIB, "soun");
        String key = "attachments/voice.m4a";
        fixture.put(key, voice);
        fixture.failRangeGets(10);

        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> controller.serve(request(key, null), new JwtPrincipal(UUID.randomUUID())));

        assertThat(ex.getCode()).isEqualTo(ApiErrorCode.INTERNAL_ERROR);
        awaitIdle();
        fixture.failRangeGets(0);
        fixture.reset();

        Captured retry = serve(key, null);
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.body()).containsExactly(voice);
    }

    @Test
    void clientAbortReleasesTheUpstreamResponse() throws Exception {
        byte[] large = png(25 * MIB);
        String key = "attachments/large.png";
        fixture.put(key, large);

        ResponseEntity<?> response = controller.serve(
                request(key, null), new JwtPrincipal(UUID.randomUUID()));
        try (InputStream body = ((Resource) response.getBody()).getInputStream()) {
            assertThat(body.readNBytes(1024)).hasSize(1024);
        }

        awaitIdle();
        assertThat(fixture.activeGets()).isZero();
    }

    // ------------------------------------------- bounded worker admission

    @Test
    void concurrentReadsAreBoundByTheCallingThreadsAndAllRecover() throws Exception {
        String key = "attachments/shared.png";
        fixture.put(key, png(200 * 1024));
        fixture.delayGetHeaders(Duration.ofMillis(150));
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                tasks.add(() -> storage.readPrefix(key, 1024).bytes().length);
            }
            List<Future<Integer>> futures = workers.invokeAll(tasks, 30, TimeUnit.SECONDS);
            for (Future<Integer> future : futures) {
                assertThat(future.get()).isEqualTo(1024);
            }
        } finally {
            workers.shutdownNow();
        }

        assertThat(fixture.peakActiveGets()).isBetween(1, 4);
        assertThat(fixture.upstreamBytes()).isEqualTo(8 * 1024L);
    }

    // -------------------------------------------------------------- helpers

    private Captured serve(String key, String range) throws IOException {
        return serve(controller, key, range);
    }

    private Captured serve(FileController target, String key, String range) throws IOException {
        ResponseEntity<?> response = target.serve(
                request(key, range), new JwtPrincipal(UUID.randomUUID()));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        servletResponse.setStatus(response.getStatusCodeValue());
        response.getHeaders().forEach((name, values) ->
                values.forEach(value -> servletResponse.addHeader(name, value)));
        byte[] body = null;
        if (response.getBody() != null) {
            new ResourceHttpMessageConverter().write(
                    (Resource) response.getBody(),
                    response.getHeaders().getContentType(),
                    new ServletServerHttpResponse(servletResponse));
            body = servletResponse.getContentAsByteArray();
        }
        return new Captured(response.getStatusCodeValue(), servletResponse, body);
    }

    private static MockHttpServletRequest request(String key, String range) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE,
                "/api/v1/files/" + key);
        if (range != null) {
            request.addHeader("Range", range);
        }
        return request;
    }

    private void awaitIdle() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (fixture.activeGets() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(fixture.activeGets()).isZero();
    }

    private record Captured(int status, MockHttpServletResponse response, byte[] body) {

        String header(String name) {
            return response.getHeader(name);
        }
    }

    private static byte[] png(int size) {
        byte[] out = new byte[size];
        System.arraycopy(PNG_MAGIC, 0, out, 0, PNG_MAGIC.length);
        for (int i = PNG_MAGIC.length; i < size; i++) {
            out[i] = (byte) ('a' + (i % 26));
        }
        return out;
    }

    private static byte[] isoBmff(int mdatBytes, String... handlers) {
        byte[] ftyp = box("ftyp", new byte[8]);
        byte[] mdat = box("mdat", new byte[mdatBytes]);
        byte[] moovPayload = new byte[0];
        for (String handler : handlers) {
            byte[] hdlrPayload = new byte[16];
            System.arraycopy(handler.getBytes(StandardCharsets.US_ASCII), 0, hdlrPayload, 8, 4);
            moovPayload = concat(moovPayload, box("trak", box("mdia", box("hdlr", hdlrPayload))));
        }
        return concat(ftyp, mdat, box("moov", moovPayload));
    }

    private static byte[] box(String type, byte[] payload) {
        int size = 8 + payload.length;
        byte[] out = new byte[size];
        out[0] = (byte) (size >>> 24);
        out[1] = (byte) (size >>> 16);
        out[2] = (byte) (size >>> 8);
        out[3] = (byte) size;
        for (int i = 0; i < 4; i++) {
            out[4 + i] = (byte) type.charAt(i);
        }
        System.arraycopy(payload, 0, out, 8, payload.length);
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    /**
     * Minimal path-style S3 surface with byte accounting, delay injection and
     * a one-shot failure for range reads. It deliberately does not verify
     * signatures; no credential material is involved.
     */
    static final class FakeS3 implements AutoCloseable {

        record Call(String method, String key, String range, int status, long bodyBytes) {
        }

        private final HttpServer server;
        private final java.util.concurrent.ConcurrentHashMap<String, byte[]> objects =
                new java.util.concurrent.ConcurrentHashMap<>();
        private final List<Call> calls = new CopyOnWriteArrayList<>();
        private final AtomicLong upstreamBytes = new AtomicLong();
        private final AtomicInteger getAttempts = new AtomicInteger();
        private final AtomicInteger activeGets = new AtomicInteger();
        private final AtomicInteger peakActiveGets = new AtomicInteger();
        private final AtomicInteger failRangeGets = new AtomicInteger();
        private volatile Duration getHeaderDelay = Duration.ZERO;

        FakeS3() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "budget-s3");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            System.out.println("S3ResourceBudgetIT fixture: JDK HttpServer path-style S3 at "
                    + endpoint());
        }

        URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        void put(String key, byte[] bytes) {
            objects.put(key, bytes);
        }

        void reset() {
            calls.clear();
            upstreamBytes.set(0);
        }

        List<Call> calls() {
            return List.copyOf(calls);
        }

        long upstreamBytes() {
            return upstreamBytes.get();
        }

        int activeGets() {
            return activeGets.get();
        }

        int peakActiveGets() {
            return peakActiveGets.get();
        }

        int getAttempts() {
            return getAttempts.get();
        }

        void delayGetHeaders(Duration delay) {
            this.getHeaderDelay = delay;
        }

        void failRangeGets(int count) {
            this.failRangeGets.set(count);
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String method = exchange.getRequestMethod();
            String[] parts = exchange.getRequestURI().getPath().split("/", 3);
            String key = parts.length == 3 ? parts[2] : "";
            try {
                switch (method) {
                    case "HEAD" -> {
                        byte[] body = objects.get(key);
                        if (body == null) {
                            calls.add(new Call(method, key, null, 404, 0));
                            exchange.sendResponseHeaders(404, -1);
                        } else {
                            calls.add(new Call(method, key, null, 200, 0));
                            exchange.getResponseHeaders().add(
                                    "Content-Length", Long.toString(body.length));
                            exchange.sendResponseHeaders(200, -1);
                        }
                    }
                    case "DELETE" -> {
                        objects.remove(key);
                        calls.add(new Call(method, key, null, 204, 0));
                        exchange.sendResponseHeaders(204, -1);
                    }
                    case "GET" -> get(exchange, key);
                    default -> exchange.sendResponseHeaders(405, -1);
                }
            } catch (IOException clientGone) {
                // The client aborted; the call record is already written.
            } finally {
                exchange.close();
            }
        }

        private void get(HttpExchange exchange, String key) throws IOException {
            String range = exchange.getRequestHeaders().getFirst("Range");
            getAttempts.incrementAndGet();
            activeGets.incrementAndGet();
            peakActiveGets.accumulateAndGet(activeGets.get(), Math::max);
            try {
                Duration delay = getHeaderDelay;
                if (!delay.isZero()) {
                    sleep(delay);
                }
                byte[] body = objects.get(key);
                if (body == null) {
                    calls.add(new Call("GET", key, range, 404, 0));
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                if (range == null) {
                    calls.add(new Call("GET", key, null, 200, body.length));
                    exchange.sendResponseHeaders(200, body.length);
                    writeBody(exchange, body, 0, body.length);
                    return;
                }
                if (failRangeGets.getAndUpdate(value -> value > 0 ? value - 1 : 0) > 0) {
                    calls.add(new Call("GET", key, range, 500, 0));
                    exchange.sendResponseHeaders(500, -1);
                    return;
                }
                long[] slice = parseRange(range, body.length);
                if (slice == null) {
                    calls.add(new Call("GET", key, range, 416, 0));
                    exchange.getResponseHeaders().add("Content-Range", "bytes */" + body.length);
                    exchange.sendResponseHeaders(416, -1);
                    return;
                }
                long start = slice[0];
                long end = slice[1];
                long length = end - start + 1;
                calls.add(new Call("GET", key, range, 206, length));
                exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                exchange.getResponseHeaders().add("Content-Range",
                        "bytes " + start + "-" + end + "/" + body.length);
                exchange.sendResponseHeaders(206, length);
                writeBody(exchange, body, (int) start, (int) length);
            } finally {
                activeGets.decrementAndGet();
            }
        }

        private void writeBody(HttpExchange exchange, byte[] body, int start, int length)
                throws IOException {
            OutputStream out = exchange.getResponseBody();
            long written = 0;
            try {
                while (written < length) {
                    int chunk = (int) Math.min(16 * 1024L, length - written);
                    out.write(body, start + (int) written, chunk);
                    written += chunk;
                    upstreamBytes.addAndGet(chunk);
                }
                out.flush();
            } catch (IOException clientGone) {
                // Count only bytes the server actually handed to the socket.
            }
        }

        private static long[] parseRange(String header, int size) {
            if (!header.startsWith("bytes=")) {
                return null;
            }
            String spec = header.substring("bytes=".length());
            int dash = spec.indexOf('-');
            if (dash < 0) {
                return null;
            }
            String left = spec.substring(0, dash).trim();
            String right = spec.substring(dash + 1).trim();
            try {
                if (left.isEmpty()) {
                    long suffix = Long.parseLong(right);
                    if (suffix <= 0) {
                        return null;
                    }
                    return new long[] {Math.max(0, size - suffix), size - 1L};
                }
                long start = Long.parseLong(left);
                if (start >= size) {
                    return null;
                }
                long end = right.isEmpty() ? size - 1L : Math.min(Long.parseLong(right), size - 1L);
                if (end < start) {
                    return null;
                }
                return new long[] {start, end};
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        private static void sleep(Duration duration) {
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
