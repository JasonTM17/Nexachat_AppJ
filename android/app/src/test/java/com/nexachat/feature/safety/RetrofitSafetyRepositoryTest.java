package com.nexachat.feature.safety;

import com.nexachat.core.database.StarDao;
import com.nexachat.core.database.StarredMessageEntity;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okio.Timeout;
import org.junit.Before;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class RetrofitSafetyRepositoryTest {

    private TestApi api;
    private FakeStarDao starDao;
    private RetrofitSafetyRepository repository;

    @Before
    public void setUp() {
        api = new TestApi();
        starDao = new FakeStarDao();
        repository = new RetrofitSafetyRepository(api, starDao);
    }

    @Test
    public void starMessageWritesLocalRowAndCallsApi() {
        repository.starMessage("m1", "c1").blockingAwait();

        assertTrue(starDao.rows.containsKey("m1"));
        assertEquals("c1", starDao.rows.get("m1").getConversationId());
        assertEquals(1, api.starCalls);
    }

    @Test
    public void starFailureRollsBackLocalMarker() {
        api.starCall = FakeCall.error(500);
        try {
            repository.starMessage("m2", "c1").blockingAwait();
            fail("expected failure");
        } catch (SafetyApiException expected) {
            assertEquals(500, expected.getStatus());
        }
        assertFalse(starDao.rows.containsKey("m2"));
    }

    @Test
    public void unstarFailureRestoresLocalMarker() {
        repository.starMessage("m3", "c1").blockingAwait();
        api.unstarCall = FakeCall.error(503);
        try {
            repository.unstarMessage("m3", "c1").blockingAwait();
            fail("expected failure");
        } catch (SafetyApiException expected) {
            assertEquals(503, expected.getStatus());
        }
        assertTrue(starDao.rows.containsKey("m3"));
    }

    @Test
    public void blockStatusMapsDtoFlag() {
        api.blockedValue = true;
        assertTrue(repository.blockStatus("u1").blockingGet());
        api.blockedValue = false;
        assertFalse(repository.blockStatus("u1").blockingGet());
    }

    @Test
    public void blockedPageMapsItemsAndCursor() {
        api.blockedItems = 2;
        SafetyRepository.BlockedPage page = repository.listBlocked(50, null).blockingGet();
        assertEquals(2, page.items().size());
        assertEquals("user-0", page.items().get(0).userId());
        assertEquals("cursor-1", page.nextCursor());
    }

    @Test
    public void reportSendsMappedRequest() {
        repository.report("MESSAGE", "m9", ReportReason.HARASSMENT, "  details  ", null)
                .blockingAwait();
        assertEquals("MESSAGE", api.lastReport.targetType);
        assertEquals("m9", api.lastReport.targetId);
        assertEquals("HARASSMENT", api.lastReport.reason);
        assertEquals("details", api.lastReport.details);
        assertNull(api.lastReport.messageSnapshot);
    }

    @Test
    public void blankReportDetailsAreOmitted() {
        repository.report("USER", "u9", ReportReason.SPAM, "   ", null).blockingAwait();
        assertNull(api.lastReport.details);
    }

    @Test
    public void rateLimitedReportSurfacesTypedError() {
        api.reportCall = FakeCall.error(429);
        try {
            repository.report("USER", "u1", ReportReason.SPAM, null, null).blockingAwait();
            fail("expected failure");
        } catch (SafetyApiException failure) {
            assertTrue(failure.isRateLimited());
        }
    }

    @Test
    public void starredIdsStreamReflectsLocalRows() {
        repository.starMessage("m1", "c1").blockingAwait();
        assertTrue(repository.starredIds("c1").blockingFirst().contains("m1"));
        assertFalse(repository.starredIds("other").blockingFirst().contains("m1"));
    }

    private static final class TestApi implements SafetyApi {
        Call<ResponseBody> starCall = FakeCall.success(null);
        Call<ResponseBody> unstarCall = FakeCall.success(null);
        Call<ReportDto> reportCall = FakeCall.success(new ReportDto());
        boolean blockedValue;
        int blockedItems = 1;
        int starCalls;
        ReportRequest lastReport;

        @Override public Call<ResponseBody> blockUser(String userId) {
            return FakeCall.success(null);
        }

        @Override public Call<ResponseBody> unblockUser(String userId) {
            return FakeCall.success(null);
        }

        @Override public Call<BlockStatusDto> blockStatus(String userId) {
            return FakeCall.success(new BlockStatusDto(blockedValue));
        }

        @Override public Call<BlockedPageDto> listBlocked(int limit, String cursor) {
            List<BlockedUserDto> items = new ArrayList<>();
            for (int i = 0; i < blockedItems; i++) {
                items.add(new BlockedUserDto(
                        "user-" + i, "User " + i, null, null, "2026-01-01T00:00:00Z"));
            }
            return FakeCall.success(new BlockedPageDto(items, "cursor-1"));
        }

        @Override public Call<ReportDto> createReport(ReportRequest body) {
            lastReport = body;
            return reportCall;
        }

        @Override public Call<ReportPageDto> listMyReports(int limit, String cursor) {
            return FakeCall.success(new ReportPageDto());
        }

        @Override public Call<ResponseBody> starMessage(String messageId) {
            starCalls++;
            return starCall;
        }

        @Override public Call<ResponseBody> unstarMessage(String messageId) {
            return unstarCall;
        }

        @Override public Call<StarredPageDto> listStarred(int limit, String cursor) {
            return FakeCall.success(new StarredPageDto());
        }
    }

    private static final class FakeStarDao implements StarDao {
        final Map<String, StarredMessageEntity> rows = new LinkedHashMap<>();

        @Override public Completable upsert(StarredMessageEntity star) {
            return Completable.fromAction(() -> rows.put(star.getMessageId(), star));
        }

        @Override public Completable upsertAll(List<StarredMessageEntity> stars) {
            return Completable.fromAction(() -> {
                for (StarredMessageEntity star : stars) {
                    rows.put(star.getMessageId(), star);
                }
            });
        }

        @Override public Completable delete(String messageId) {
            return Completable.fromAction(() -> rows.remove(messageId));
        }

        @Override public Flowable<List<String>> starredIdsFor(String conversationId) {
            return Flowable.fromCallable(() -> {
                List<String> ids = new ArrayList<>();
                for (StarredMessageEntity row : rows.values()) {
                    if (row.getConversationId().equals(conversationId)) {
                        ids.add(row.getMessageId());
                    }
                }
                return ids;
            });
        }

        @Override public Flowable<List<StarredMessageEntity>> all() {
            return Flowable.fromCallable(() -> new ArrayList<>(rows.values()));
        }

        @Override public Completable deleteAll() {
            return Completable.fromAction(rows::clear);
        }
    }

    private static final class FakeCall<T> implements Call<T> {
        private final Response<T> response;
        private final IOException failure;

        private FakeCall(Response<T> response, IOException failure) {
            this.response = response;
            this.failure = failure;
        }

        static <T> FakeCall<T> success(T body) {
            return new FakeCall<>(Response.success(body), null);
        }

        static <T> FakeCall<T> error(int status) {
            return new FakeCall<>(Response.error(status,
                    ResponseBody.create(MediaType.get("text/plain"), "error")), null);
        }

        @Override public Response<T> execute() throws IOException {
            if (failure != null) {
                throw failure;
            }
            return response;
        }

        @Override public void enqueue(Callback<T> callback) {
            if (failure != null) {
                callback.onFailure(this, failure);
            } else {
                callback.onResponse(this, response);
            }
        }

        @Override public boolean isExecuted() {
            return true;
        }

        @Override public void cancel() { }

        @Override public boolean isCanceled() {
            return false;
        }

        @Override public Call<T> clone() {
            return new FakeCall<>(response, failure);
        }

        @Override public Request request() {
            return new Request.Builder().url("https://nexachat.app/").build();
        }

        @Override public Timeout timeout() {
            return Timeout.NONE;
        }
    }
}
