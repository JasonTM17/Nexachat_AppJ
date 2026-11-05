package com.nexachat.summary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.squareup.moshi.Moshi;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import org.junit.Test;
import retrofit2.Retrofit;
import retrofit2.converter.moshi.MoshiConverterFactory;

public final class SummaryRepositoryTest {
    private static final long EXPECTED_CACHE_TTL_MILLIS = 5 * 60 * 1000L;

    @Test
    public void retrofitUsesContractPathBodyAndParsesSummary() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        try (SummaryTestServer server = new SummaryTestServer(exchange -> {
            path.set(exchange.getRequestURI().getPath());
            requestBody.set(SummaryTestServer.requestBody(exchange));
            SummaryTestServer.respond(exchange, 200, responseJson("summary-1", false));
        })) {
            SummaryRepository repository = new SummaryRepository(api(server.baseUrl()));
            ResultCapture result = request(repository, "conversation-1",
                    new SummaryRequest(SummaryScope.LAST_N, 25, SummaryLanguage.EN, false));

            assertNotNull(result.response.get());
            assertEquals("/api/v1/conversations/conversation-1/summary", path.get());
            assertTrue(requestBody.get().contains("\"scope\":\"LAST_N\""));
            assertTrue(requestBody.get().contains("\"limit\":25"));
            assertTrue(requestBody.get().contains("\"language\":\"EN\""));
            assertTrue(requestBody.get().contains("\"refresh\":false"));
            assertEquals("conversation-1", result.response.get().getConversationId());
            assertEquals("Overview", result.response.get().getOverview());
            assertEquals("message-1", result.response.get().getKeyPoints().get(0).getMessageIds().get(0));
        }
    }

    @Test
    public void mapsServerErrorCodesToStableSummaryErrors() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (SummaryTestServer server = new SummaryTestServer(exchange -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                SummaryTestServer.respond(exchange, 400,
                        "{\"error\":{\"code\":\"TOO_FEW_MESSAGES\",\"message\":\"synthetic\"}}");
            } else if (call == 2) {
                SummaryTestServer.respond(exchange, 403,
                        "{\"error\":{\"code\":\"NOT_MEMBER\",\"message\":\"synthetic\"}}");
            } else if (call == 3) {
                SummaryTestServer.respond(exchange, 429,
                        "{\"error\":{\"code\":\"RATE_LIMITED\",\"message\":\"synthetic\"}}");
            } else if (call == 4) {
                SummaryTestServer.respond(exchange, 400,
                        "{\"error\":{\"code\":\"VALIDATION_FAILED\",\"message\":\"synthetic\"}}");
            } else if (call == 5) {
                SummaryTestServer.respond(exchange, 429,
                        "{\"error\":{\"code\":\"UNEXPECTED_CODE\",\"message\":\"synthetic\"}}");
            } else {
                SummaryTestServer.respond(exchange, 403,
                        "{\"error\":{\"code\":\"UNEXPECTED_CODE\",\"message\":\"synthetic\"}}");
            }
        })) {
            SummaryRepository repository = new SummaryRepository(api(server.baseUrl()));
            assertEquals(SummaryError.TOO_FEW, requestError(repository));
            assertEquals(SummaryError.NOT_MEMBER, requestError(repository));
            assertEquals(SummaryError.RATE_LIMITED, requestError(repository));
            assertEquals(SummaryError.UNKNOWN, requestError(repository));
            assertEquals(SummaryError.RATE_LIMITED, requestError(repository));
            assertEquals(SummaryError.NOT_MEMBER, requestError(repository));
        }
    }

    @Test
    public void networkFailureMapsToNetworkError() throws Exception {
        SummaryTestServer server = new SummaryTestServer(exchange ->
                SummaryTestServer.respond(exchange, 200, responseJson("unused", false)));
        String unavailableUrl = server.baseUrl();
        server.close();

        assertEquals(SummaryError.NETWORK,
                requestError(new SummaryRepository(api(unavailableUrl))));
    }

    @Test
    public void cacheExpiresAtFiveMinutesAndReportsCachedResponses() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong now = new AtomicLong(1_000L);
        try (SummaryTestServer server = new SummaryTestServer(exchange -> {
            int call = calls.incrementAndGet();
            SummaryTestServer.respond(exchange, 200, responseJson("summary-" + call, false));
        })) {
            SummaryRepository repository = new SummaryRepository(api(server.baseUrl()), now::get);
            ResultCapture original = request(repository, "conversation-1",
                    new SummaryRequest(SummaryScope.UNREAD, null, SummaryLanguage.AUTO, false));
            assertEquals("summary-1", original.response.get().getSummaryId());
            assertFalse(original.response.get().isCached());

            now.addAndGet(EXPECTED_CACHE_TTL_MILLIS - 1L);
            ResultCapture cached = request(repository, "conversation-1",
                    new SummaryRequest(SummaryScope.UNREAD, null, SummaryLanguage.AUTO, false));
            assertEquals("summary-1", cached.response.get().getSummaryId());
            assertTrue(cached.response.get().isCached());
            assertEquals(1, calls.get());

            now.incrementAndGet();
            ResultCapture expired = request(repository, "conversation-1",
                    new SummaryRequest(SummaryScope.UNREAD, null, SummaryLanguage.AUTO, false));
            assertEquals("summary-2", expired.response.get().getSummaryId());
            assertFalse(expired.response.get().isCached());
            assertEquals(2, calls.get());
        }
    }

    @Test
    public void cacheIsKeyedByConversationAndScope() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (SummaryTestServer server = new SummaryTestServer(exchange -> {
            int call = calls.incrementAndGet();
            SummaryTestServer.respond(exchange, 200, responseJson("summary-" + call, false));
        })) {
            SummaryRepository repository = new SummaryRepository(api(server.baseUrl()));

            assertEquals("summary-1", request(repository, "conversation-1",
                    new SummaryRequest(SummaryScope.UNREAD, null, SummaryLanguage.AUTO, false))
                    .response.get().getSummaryId());
            assertEquals("summary-1", request(repository, "conversation-1",
                    new SummaryRequest(SummaryScope.UNREAD, null, SummaryLanguage.AUTO, false))
                    .response.get().getSummaryId());
            assertEquals(1, calls.get());

            assertEquals("summary-2", request(repository, "conversation-2",
                    new SummaryRequest(SummaryScope.UNREAD, null, SummaryLanguage.AUTO, false))
                    .response.get().getSummaryId());
            assertEquals("summary-3", request(repository, "conversation-1",
                    new SummaryRequest(SummaryScope.TODAY, null, SummaryLanguage.AUTO, false))
                    .response.get().getSummaryId());
            assertEquals(3, calls.get());
        }
    }

    @Test
    public void findCachedByIdReturnsOnlyTheSavedSummary() throws Exception {
        try (SummaryTestServer server = new SummaryTestServer(exchange ->
                SummaryTestServer.respond(exchange, 200, responseJson("summary-1", false)))) {
            SummaryRepository repository = new SummaryRepository(api(server.baseUrl()));
            request(repository, "conversation-1",
                    new SummaryRequest(SummaryScope.UNREAD, null, SummaryLanguage.AUTO, false));

            assertTrue(repository.findCachedById("conversation-1", SummaryScope.UNREAD, "summary-1")
                    .isPresent());
            assertFalse(repository.findCachedById("conversation-1", SummaryScope.UNREAD, "summary-2")
                    .isPresent());
            assertFalse(repository.findCachedById("conversation-2", SummaryScope.UNREAD, "summary-1")
                    .isPresent());
        }
    }

    private static SummaryError requestError(SummaryRepository repository) throws InterruptedException {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<SummaryError> error = new AtomicReference<>();
        repository.request("conversation-1",
                new SummaryRequest(SummaryScope.UNREAD, null, SummaryLanguage.AUTO, false),
                new SummaryRepository.Callback() {
                    @Override
                    public void onSuccess(SummaryResponse response) {
                        completed.countDown();
                    }

                    @Override
                    public void onError(SummaryError value) {
                        error.set(value);
                        completed.countDown();
                    }
                });
        await(completed);
        return error.get();
    }

    private static ResultCapture request(
            SummaryRepository repository,
            String conversationId,
            SummaryRequest request) throws InterruptedException {
        ResultCapture capture = new ResultCapture();
        repository.request(conversationId, request, capture);
        await(capture.completed);
        return capture;
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue("summary request timed out", latch.await(5, TimeUnit.SECONDS));
    }

    static SummaryApi api(String baseUrl) {
        Moshi moshi = new Moshi.Builder().build();
        return new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(new OkHttpClient())
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
                .create(SummaryApi.class);
    }

    static String responseJson(String summaryId, boolean cached) {
        return "{\"summaryId\":\"" + summaryId + "\",\"conversationId\":\"conversation-1\","
                + "\"fromSeq\":1,\"toSeq\":4,\"messageCount\":4,\"language\":\"VI\","
                + "\"generatedBy\":\"LOCAL\",\"model\":null,\"fallbackReason\":null,"
                + "\"cached\":" + cached + ",\"createdAt\":\"2026-11-05T10:00:00Z\","
                + "\"overview\":\"Overview\",\"keyPoints\":[{\"text\":\"Point\","
                + "\"messageIds\":[\"message-1\"]}],\"decisions\":[],\"actionItems\":[],"
                + "\"questions\":[],\"topics\":[\"topic\"],\"participants\":[]}";
    }

    private static final class ResultCapture implements SummaryRepository.Callback {
        private final CountDownLatch completed = new CountDownLatch(1);
        private final AtomicReference<SummaryResponse> response = new AtomicReference<>();
        private final AtomicReference<SummaryError> error = new AtomicReference<>();

        @Override
        public void onSuccess(SummaryResponse value) {
            response.set(value);
            completed.countDown();
        }

        @Override
        public void onError(SummaryError value) {
            error.set(value);
            completed.countDown();
        }
    }
}
