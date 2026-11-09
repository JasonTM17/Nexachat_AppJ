package com.nexachat.feature.archive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okio.Timeout;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public final class ArchiveRepositoryTest {

    @Test
    public void passesTheFrozenFiltersAndOpaqueCursorToRetrofit() {
        FakeApi api = new FakeApi(Response.success(new ArchivePage()));
        RetrofitArchiveRepository repository = new RetrofitArchiveRepository(api);
        AtomicReference<ArchivePage> received = new AtomicReference<>();
        ArchiveQuery query = new ArchiveQuery(
                "conversation-1", ArchiveType.LINK, "sender-2",
                "2026-11-01T00:00:00Z", "2026-11-09T23:59:59.999999999Z",
                30, "opaque/next?cursor=value");

        repository.load(query, new ArchiveRepository.Callback() {
            @Override public void onSuccess(ArchivePage page) { received.set(page); }
            @Override public void onFailure(ArchiveRepository.Failure failure) { }
        });

        assertEquals("conversation-1", api.conversationId);
        assertEquals("LINK", api.type);
        assertEquals("sender-2", api.senderId);
        assertEquals("2026-11-01T00:00:00Z", api.from);
        assertEquals("2026-11-09T23:59:59.999999999Z", api.to);
        assertEquals(30, api.limit);
        assertEquals("opaque/next?cursor=value", api.cursor);
        assertSame(api.call.response.body(), received.get());
    }

    @Test
    public void mapsForbiddenAndIoFailuresWithoutExposingResponseDetails() {
        AtomicReference<ArchiveRepository.Failure> failure = new AtomicReference<>();
        FakeApi forbiddenApi = new FakeApi(Response.error(
                403, ResponseBody.create(MediaType.parse("application/json"),
                        "{\"error\":{\"code\":\"NOT_MEMBER\"}}")));
        new RetrofitArchiveRepository(forbiddenApi).load(query(), new ArchiveRepository.Callback() {
            @Override public void onSuccess(ArchivePage page) { }
            @Override public void onFailure(ArchiveRepository.Failure value) { failure.set(value); }
        });
        assertEquals(ArchiveRepository.Failure.FORBIDDEN, failure.get());

        FakeApi offlineApi = new FakeApi(new IOException("private transport detail"));
        new RetrofitArchiveRepository(offlineApi).load(query(), new ArchiveRepository.Callback() {
            @Override public void onSuccess(ArchivePage page) { }
            @Override public void onFailure(ArchiveRepository.Failure value) { failure.set(value); }
        });
        assertEquals(ArchiveRepository.Failure.OFFLINE, failure.get());
    }

    private static ArchiveQuery query() {
        return new ArchiveQuery("conversation-1", ArchiveType.MEDIA, null, null, null, 30, null);
    }

    private static final class FakeApi implements ArchiveApi {
        private final FakeCall call;
        private String conversationId;
        private String type;
        private String senderId;
        private String from;
        private String to;
        private int limit;
        private String cursor;

        private FakeApi(Response<ArchivePage> response) {
            call = new FakeCall(response, null);
        }

        private FakeApi(IOException failure) {
            call = new FakeCall(null, failure);
        }

        @Override
        public Call<ArchivePage> loadArchive(
                String conversationId,
                String type,
                String senderId,
                String from,
                String to,
                int limit,
                String cursor) {
            this.conversationId = conversationId;
            this.type = type;
            this.senderId = senderId;
            this.from = from;
            this.to = to;
            this.limit = limit;
            this.cursor = cursor;
            return call;
        }
    }

    private static final class FakeCall implements Call<ArchivePage> {
        private final Response<ArchivePage> response;
        private final IOException failure;
        private boolean executed;
        private boolean cancelled;

        private FakeCall(Response<ArchivePage> response, IOException failure) {
            this.response = response;
            this.failure = failure;
        }

        @Override
        public Response<ArchivePage> execute() throws IOException {
            executed = true;
            if (failure != null) throw failure;
            return response;
        }

        @Override
        public void enqueue(Callback<ArchivePage> callback) {
            executed = true;
            if (failure != null) {
                callback.onFailure(this, failure);
            } else {
                callback.onResponse(this, response);
            }
        }

        @Override public boolean isExecuted() { return executed; }
        @Override public void cancel() { cancelled = true; }
        @Override public boolean isCanceled() { return cancelled; }
        @Override public Call<ArchivePage> clone() { return new FakeCall(response, failure); }
        @Override public Request request() { return new Request.Builder().url("https://example.test").build(); }
        @Override public Timeout timeout() { return Timeout.NONE; }
    }
}
