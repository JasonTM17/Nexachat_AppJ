package com.nexachat.summary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

import com.squareup.moshi.Moshi;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.di.SummaryModule;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okio.Timeout;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;
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
            assertTrue(requestBody.get().contains("\"timeZone\":"));
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
            } else if (call == 6) {
                SummaryTestServer.respond(exchange, 403,
                        "{\"error\":{\"code\":\"UNEXPECTED_CODE\",\"message\":\"synthetic\"}}");
            } else if (call == 7) {
                SummaryTestServer.respond(exchange, 409,
                        "{\"error\":{\"code\":\"E2EE_AI_UNAVAILABLE\",\"message\":\"synthetic\"}}");
            } else {
                SummaryTestServer.respond(exchange, 400,
                        "{\"error\":{\"code\":\"INVALID_TIME_ZONE\",\"message\":\"synthetic\"}}");
            }
        })) {
            SummaryRepository repository = new SummaryRepository(api(server.baseUrl()));
            assertEquals(SummaryError.TOO_FEW, requestError(repository));
            assertEquals(SummaryError.NOT_MEMBER, requestError(repository));
            assertEquals(SummaryError.RATE_LIMITED, requestError(repository));
            assertEquals(SummaryError.UNKNOWN, requestError(repository));
            assertEquals(SummaryError.RATE_LIMITED, requestError(repository));
            assertEquals(SummaryError.NOT_MEMBER, requestError(repository));
            assertEquals(SummaryError.E2EE_UNAVAILABLE, requestError(repository));
            assertEquals(SummaryError.INVALID_TIME_ZONE, requestError(repository));
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
            ResultCapture original = request(repository, "conversation-1", unread());
            assertEquals("summary-1", original.response.get().getSummaryId());
            assertFalse(original.response.get().isCached());

            now.addAndGet(EXPECTED_CACHE_TTL_MILLIS - 1L);
            ResultCapture cached = request(repository, "conversation-1", unread());
            assertEquals("summary-1", cached.response.get().getSummaryId());
            assertTrue(cached.response.get().isCached());
            assertEquals(1, calls.get());

            now.incrementAndGet();
            ResultCapture expired = request(repository, "conversation-1", unread());
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

            assertEquals("summary-1", request(repository, "conversation-1", unread())
                    .response.get().getSummaryId());
            assertEquals("summary-1", request(repository, "conversation-1", unread())
                    .response.get().getSummaryId());
            assertEquals(1, calls.get());

            assertEquals("summary-2", request(repository, "conversation-2", unread())
                    .response.get().getSummaryId());
            assertEquals("summary-3", request(repository, "conversation-1",
                    new SummaryRequest(SummaryScope.TODAY, null, SummaryLanguage.AUTO, false))
                    .response.get().getSummaryId());
            assertEquals(3, calls.get());
        }
    }

    @Test
    public void cacheIsKeyedByCountZoneAndLocalDay() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        // 23:58 in Vietnam; four minutes later the local day has changed but the entry is young.
        AtomicLong now = new AtomicLong(Instant.parse("2026-11-17T16:58:00Z").toEpochMilli());
        try (SummaryTestServer server = new SummaryTestServer(exchange -> {
            int call = calls.incrementAndGet();
            SummaryTestServer.respond(exchange, 200, responseJson("summary-" + call, false));
        })) {
            SummaryRepository repository = new SummaryRepository(api(server.baseUrl()), now::get);

            request(repository, "conversation-1", lastN(20, "Asia/Ho_Chi_Minh"));
            request(repository, "conversation-1", lastN(20, "Asia/Ho_Chi_Minh"));
            assertEquals(1, calls.get());

            request(repository, "conversation-1", lastN(50, "Asia/Ho_Chi_Minh"));
            assertEquals(2, calls.get());

            request(repository, "conversation-1", today("Asia/Ho_Chi_Minh"));
            request(repository, "conversation-1", today("Asia/Ho_Chi_Minh"));
            assertEquals(3, calls.get());

            // Same local day, different zone id: the key names the zone, so it is not replayed.
            request(repository, "conversation-1", today("+07:00"));
            assertEquals(4, calls.get());

            now.addAndGet(TimeUnit.MINUTES.toMillis(4));
            request(repository, "conversation-1", today("Asia/Ho_Chi_Minh"));
            assertEquals(5, calls.get());
        }
    }

    @Test
    public void losingMembershipDropsEveryCachedSummary() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (SummaryTestServer server = new SummaryTestServer(exchange -> {
            int call = calls.incrementAndGet();
            if (call == 2) {
                SummaryTestServer.respond(exchange, 403,
                        "{\"error\":{\"code\":\"NOT_MEMBER\",\"message\":\"synthetic\"}}");
            } else {
                SummaryTestServer.respond(exchange, 200, responseJson("summary-" + call, false));
            }
        })) {
            SummaryRepository repository = new SummaryRepository(api(server.baseUrl()));
            request(repository, "conversation-1", unread());
            assertEquals(1, calls.get());

            ResultCapture denied = request(repository, "conversation-1",
                    new SummaryRequest(SummaryScope.UNREAD, null, SummaryLanguage.AUTO, true));
            assertEquals(SummaryError.NOT_MEMBER, denied.error.get());

            // The entry cached before access was lost must not be replayed.
            ResultCapture after = request(repository, "conversation-1", unread());
            assertEquals("summary-3", after.response.get().getSummaryId());
            assertEquals(3, calls.get());
        }
    }

    @Test
    public void clearCacheForcesTheNextRequestToTheNetwork() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (SummaryTestServer server = new SummaryTestServer(exchange -> {
            int call = calls.incrementAndGet();
            SummaryTestServer.respond(exchange, 200, responseJson("summary-" + call, false));
        })) {
            SummaryRepository repository = new SummaryRepository(api(server.baseUrl()));
            request(repository, "conversation-1", unread());
            request(repository, "conversation-1", unread());
            assertEquals(1, calls.get());

            repository.clearCache();

            assertEquals("summary-2", request(repository, "conversation-1", unread())
                    .response.get().getSummaryId());
            assertEquals(2, calls.get());
        }
    }

    @Test
    public void findCachedByIdReturnsOnlyTheSavedSummary() throws Exception {
        try (SummaryTestServer server = new SummaryTestServer(exchange ->
                SummaryTestServer.respond(exchange, 200, responseJson("summary-1", false)))) {
            SummaryRepository repository = new SummaryRepository(api(server.baseUrl()));
            request(repository, "conversation-1", unread());

            assertTrue(repository.findCachedById("conversation-1", unread(), "summary-1")
                    .isPresent());
            assertFalse(repository.findCachedById("conversation-1", unread(), "summary-2")
                    .isPresent());
            assertFalse(repository.findCachedById("conversation-2", unread(), "summary-1")
                    .isPresent());
            assertFalse(repository.findCachedById("conversation-1", lastN(20, "Asia/Ho_Chi_Minh"),
                    "summary-1").isPresent());
        }
    }

    @Test
    public void cacheClearFencesAPendingResponseAndItsSuccessCallback() {
        ControlledApi api = new ControlledApi();
        SummaryRepository repository = new SummaryRepository(api);
        ResultCapture old = new ResultCapture();
        repository.request("conversation-1", unread(), old);

        repository.clearCache();
        api.calls.get(0).succeed(summary("old"));

        assertNull(old.response.get());
        assertFalse(repository.findCachedById("conversation-1", unread(), "old").isPresent());
        repository.request("conversation-1", unread(), new ResultCapture());
        assertEquals(2, api.calls.size());
    }

    @Test
    public void aNewSessionWithTheSameConsentCannotReplayThePreviousAccountsSummary() {
        ControlledApi api = new ControlledApi();
        SessionEvents sessions = new SessionEvents();
        SummaryRepository repository = SummaryModule.provideSummaryRepository(api, sessions);
        sessions.emitSessionAvailable();
        repository.request("conversation-1", unread(), new ResultCapture());
        api.calls.get(0).succeed(summary("account-a"));
        assertTrue(repository.findCachedById("conversation-1", unread(), "account-a").isPresent());

        sessions.emitSessionClosing();
        sessions.emitLoggedOut();
        sessions.emitSessionAvailable();
        ResultCapture accountB = new ResultCapture();
        repository.request("conversation-1", unread(), accountB);

        assertNull(accountB.response.get());
        assertEquals(2, api.calls.size());
        api.calls.get(1).succeed(summary("account-b"));
        assertEquals("account-b", accountB.response.get().getSummaryId());
    }

    @Test
    public void sessionClosingFencesAPendingFillAndBlocksNewRequestsUntilLogin() {
        ControlledApi api = new ControlledApi();
        SessionEvents sessions = new SessionEvents();
        SummaryRepository repository = new SummaryRepository(api, sessions);
        sessions.emitSessionAvailable();
        ResultCapture old = new ResultCapture();
        repository.request("conversation-1", unread(), old);

        sessions.emitSessionClosing();
        api.calls.get(0).succeed(summary("closed-session"));
        assertNull(old.response.get());
        assertFalse(repository.findCachedById("conversation-1", unread(), "closed-session").isPresent());
        ResultCapture closed = new ResultCapture();
        repository.request("conversation-1", unread(), closed);
        assertEquals(SummaryError.UNKNOWN, closed.error.get());
        assertEquals(1, api.calls.size());

        sessions.emitSessionAvailable();
        repository.request("conversation-1", unread(), new ResultCapture());
        assertEquals(2, api.calls.size());
    }

    @Test
    public void directAccountReplacementAndReauthenticationInvalidateSummaryCache() {
        ControlledApi api = new ControlledApi();
        SessionEvents sessions = new SessionEvents();
        SummaryRepository repository = new SummaryRepository(api, sessions);
        sessions.emitSessionAvailable();
        repository.request("conversation-1", unread(), new ResultCapture());
        api.calls.get(0).succeed(summary("account-a"));

        sessions.emitSessionAvailable();
        repository.request("conversation-1", unread(), new ResultCapture());
        assertEquals(2, api.calls.size());
        api.calls.get(1).succeed(summary("account-b"));
        sessions.emitReauthenticationRequired();
        assertFalse(repository.findCachedById("conversation-1", unread(), "account-b").isPresent());
    }

    @Test
    public void anOlderResponseCannotReplaceANewerRefresh() {
        ControlledApi api = new ControlledApi();
        SummaryRepository repository = new SummaryRepository(api);
        ResultCapture old = new ResultCapture();
        repository.request("conversation-1", unread(), old);
        repository.request("conversation-1", refreshUnread(), new ResultCapture());

        api.calls.get(1).succeed(summary("fresh"));
        api.calls.get(0).succeed(summary("old"));

        assertEquals("old", old.response.get().getSummaryId());
        assertTrue(repository.findCachedById("conversation-1", unread(), "fresh").isPresent());
        assertFalse(repository.findCachedById("conversation-1", unread(), "old").isPresent());
    }

    @Test
    public void aFailedNewerRefreshDoesNotRestoreOlderFillEligibility() {
        ControlledApi api = new ControlledApi();
        SummaryRepository repository = new SummaryRepository(api);
        repository.request("conversation-1", unread(), new ResultCapture());
        ResultCapture fresh = new ResultCapture();
        repository.request("conversation-1", refreshUnread(), fresh);

        api.calls.get(1).fail(new IOException("synthetic"));
        api.calls.get(0).succeed(summary("old"));

        assertEquals(SummaryError.NETWORK, fresh.error.get());
        assertFalse(repository.findCachedById("conversation-1", unread(), "old").isPresent());
        repository.request("conversation-1", unread(), new ResultCapture());
        assertEquals(3, api.calls.size());
    }

    @Test
    public void distinctKeysCanFillIndependentlyAndCanceledCallsCannotFill() {
        ControlledApi api = new ControlledApi();
        SummaryRepository repository = new SummaryRepository(api);
        SummaryRepository.Cancellable canceled = repository.request(
                "conversation-1", unread(), new ResultCapture());
        repository.request("conversation-2", unread(), new ResultCapture());
        canceled.cancel();
        api.calls.get(0).succeed(summary("canceled"));
        api.calls.get(1).succeed(summary("other"));

        assertFalse(repository.findCachedById("conversation-1", unread(), "canceled").isPresent());
        assertTrue(repository.findCachedById("conversation-2", unread(), "other").isPresent());
    }

    @Test
    public void enqueueFailureIsReportedAndTheNextRequestCanRecover() {
        ControlledApi api = new ControlledApi();
        api.enqueueFailure = true;
        SummaryRepository repository = new SummaryRepository(api);
        ResultCapture failed = new ResultCapture();

        repository.request("conversation-1", unread(), failed);

        assertEquals(SummaryError.UNKNOWN, failed.error.get());
        api.enqueueFailure = false;
        ResultCapture next = new ResultCapture();
        repository.request("conversation-1", unread(), next);
        api.calls.get(1).succeed(summary("recovered"));
        assertTrue(repository.findCachedById("conversation-1", unread(), "recovered").isPresent());
    }

    @Test
    public void requestedLanguagesHaveIndependentCacheIdentity() {
        ControlledApi api = new ControlledApi();
        SummaryRepository repository = new SummaryRepository(api);
        repository.request("conversation-1", unread(), new ResultCapture());
        api.calls.get(0).succeed(summary("auto"));

        repository.request("conversation-1",
                new SummaryRequest(SummaryScope.UNREAD, null, SummaryLanguage.EN, false),
                new ResultCapture());

        assertEquals(2, api.calls.size());
    }

    private static SummaryRequest refreshUnread() {
        return new SummaryRequest(SummaryScope.UNREAD, null, SummaryLanguage.AUTO, true);
    }

    private static SummaryResponse summary(String id) {
        return new SummaryResponse(id, "conversation-1", 1, 5, 5, SummaryLanguage.EN,
                "LOCAL", null, null, false, "2026-11-21T10:00:00Z", "Overview",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    static final class ControlledApi implements SummaryApi {
        final List<ControlledCall> calls = new ArrayList<>();
        private boolean enqueueFailure;

        @Override
        public Call<SummaryResponse> summarize(String id, SummaryRequest request) {
            ControlledCall call = new ControlledCall(enqueueFailure);
            calls.add(call);
            return call;
        }
    }

    static final class ControlledCall implements Call<SummaryResponse> {
        private final boolean enqueueFailure;
        private retrofit2.Callback<SummaryResponse> callback;
        private boolean canceled;

        private ControlledCall(boolean enqueueFailure) {
            this.enqueueFailure = enqueueFailure;
        }

        @Override
        public void enqueue(retrofit2.Callback<SummaryResponse> value) {
            if (enqueueFailure) {
                throw new IllegalStateException("synthetic enqueue failure");
            }
            callback = value;
        }

        void succeed(SummaryResponse response) { callback.onResponse(this, Response.success(response)); }
        void fail(Throwable failure) { callback.onFailure(this, failure); }
        void error(int code, String body) {
            callback.onResponse(this, Response.error(code,
                    ResponseBody.create(okhttp3.MediaType.get("application/json"), body)));
        }
        @Override public Response<SummaryResponse> execute() { throw new UnsupportedOperationException(); }
        @Override public boolean isExecuted() { return callback != null; }
        @Override public void cancel() { canceled = true; }
        @Override public boolean isCanceled() { return canceled; }
        @Override public Call<SummaryResponse> clone() { return new ControlledCall(enqueueFailure); }
        @Override public Request request() { return new Request.Builder().url("http://localhost/").build(); }
        @Override public Timeout timeout() { return Timeout.NONE; }
    }

    private static SummaryRequest unread() {
        return new SummaryRequest(SummaryScope.UNREAD, null, SummaryLanguage.AUTO, false);
    }

    private static SummaryRequest lastN(int limit, String zone) {
        return new SummaryRequest(SummaryScope.LAST_N, limit, SummaryLanguage.AUTO, false, zone);
    }

    private static SummaryRequest today(String zone) {
        return new SummaryRequest(SummaryScope.TODAY, null, SummaryLanguage.AUTO, false, zone);
    }

    private static SummaryError requestError(SummaryRepository repository) throws InterruptedException {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<SummaryError> error = new AtomicReference<>();
        repository.request("conversation-1", unread(),
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
        return responseJson(summaryId, cached, null, null, null);
    }

    static String responseJson(
            String summaryId,
            boolean cached,
            String scope,
            Integer limit,
            String periodJson) {
        StringBuilder json = new StringBuilder()
                .append("{\"summaryId\":\"").append(summaryId).append("\",")
                .append("\"conversationId\":\"conversation-1\",")
                .append("\"fromSeq\":1,\"toSeq\":4,\"messageCount\":4,\"language\":\"VI\",")
                .append("\"generatedBy\":\"LOCAL\",\"model\":null,\"fallbackReason\":null,")
                .append("\"cached\":").append(cached).append(',')
                .append("\"createdAt\":\"2026-11-05T10:00:00Z\",")
                .append("\"overview\":\"Overview\",\"keyPoints\":[{\"text\":\"Point\",")
                .append("\"messageIds\":[\"message-1\"]}],\"decisions\":[],\"actionItems\":[],")
                .append("\"questions\":[],\"topics\":[\"topic\"],\"participants\":[]");
        if (scope != null) {
            json.append(",\"scope\":\"").append(scope).append('"');
        }
        if (limit != null) {
            json.append(",\"limit\":").append(limit);
        }
        if (periodJson != null) {
            json.append(",\"period\":").append(periodJson);
        }
        return json.append('}').toString();
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
