package com.nexachat.feature.reminder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.ResponseBody;
import okio.Timeout;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public final class ReminderRepositoryTest {

    @Test
    public void listsInRemindAtOrderAndSkipsMalformedRows() {
        ReminderDto later = dto("later", "2026-12-02T02:00:00Z", "WEEKLY");
        ReminderDto earlier = dto("earlier", "2026-12-01T02:00:00Z", null);
        ReminderDto broken = new ReminderDto(
                "broken", "conversation-1", "Broken", "not-a-time", "NONE",
                "user-1", "Binh", true, "2026-11-09T09:00:00Z",
                "2026-11-09T09:00:00Z");
        FakeApi api = FakeApi.forList(Response.success(List.of(later, broken, earlier)));

        AtomicReference<List<Reminder>> received = new AtomicReference<>();
        new RetrofitReminderRepository(api).load("conversation-1",
                new ReminderRepository.ListCallback() {
                    @Override
                    public void onSuccess(List<Reminder> reminders) {
                        received.set(reminders);
                    }

                    @Override
                    public void onFailure(ReminderRepository.Failure failure) {
                    }
                });

        assertEquals(2, received.get().size());
        assertEquals("earlier", received.get().get(0).getId());
        assertEquals(ReminderRepeat.NONE, received.get().get(0).getRepeat());
        assertEquals("later", received.get().get(1).getId());
        assertEquals(ReminderRepeat.WEEKLY, received.get().get(1).getRepeat());
        assertEquals("conversation-1", api.conversationId);
    }

    @Test
    public void createsWithATrimmedPayloadAndAnIdempotencyKey() {
        FakeApi api = FakeApi.forItem(Response.success(dto(
                "created", "2026-12-01T02:00:00Z", "DAILY")));
        RetrofitReminderRepository repository = new RetrofitReminderRepository(api);
        ReminderDraft draft = new ReminderDraft(
                "  Họp team  ", Instant.parse("2026-12-01T02:00:00.123Z"),
                ReminderRepeat.DAILY);

        AtomicReference<Reminder> received = new AtomicReference<>();
        repository.create("conversation-1", draft, "key-1", itemCallback(received));

        assertEquals("key-1", api.idempotencyKey);
        assertEquals("Họp team", api.request.getTitle());
        assertEquals("2026-12-01T02:00:00Z", api.request.getRemindAt());
        assertEquals("DAILY", api.request.getRepeat());
        assertEquals("created", received.get().getId());
    }

    @Test
    public void patchesOnlyTheProvidedFields() {
        FakeApi api = FakeApi.forItem(Response.success(dto(
                "edited", "2026-12-05T02:00:00Z", "MONTHLY")));
        RetrofitReminderRepository repository = new RetrofitReminderRepository(api);

        repository.update("conversation-1", "edited", "New title", null, null,
                itemCallback(new AtomicReference<>()));

        assertEquals("New title", api.request.getTitle());
        assertNull(api.request.getRemindAt());
        assertNull(api.request.getRepeat());
    }

    @Test
    public void mapsServerFailuresWithoutExposingMessages() {
        assertEquals(ReminderRepository.Failure.NOT_MEMBER,
                mapFailure(403, "{\"error\":{\"code\":\"NOT_MEMBER\"}}"));
        assertEquals(ReminderRepository.Failure.NOT_ADMIN,
                mapFailure(403, "{\"error\":{\"code\":\"NOT_ADMIN\"}}"));
        assertEquals(ReminderRepository.Failure.FORBIDDEN,
                mapFailure(403, "{\"error\":{\"code\":\"FORBIDDEN\"}}"));
        assertEquals(ReminderRepository.Failure.NOT_FOUND,
                mapFailure(404, "{\"error\":{\"code\":\"NOT_FOUND\"}}"));
        assertEquals(ReminderRepository.Failure.VALIDATION,
                mapFailure(400, "{\"error\":{\"code\":\"VALIDATION_FAILED\"}}"));
        assertEquals(ReminderRepository.Failure.LIMIT,
                mapFailure(400, "{\"error\":{\"code\":\"VALIDATION_FAILED\","
                        + "\"details\":{\"limit\":50}}}"));
        assertEquals(ReminderRepository.Failure.CONFLICT,
                mapFailure(409, "{\"error\":{\"code\":\"IDEMPOTENCY_KEY_REUSED\"}}"));
        assertEquals(ReminderRepository.Failure.RATE_LIMITED, mapFailure(429, null));
        assertEquals(ReminderRepository.Failure.UNKNOWN, mapFailure(500, null));
    }

    @Test
    public void deleteTreats204AsSuccessAndMissingRowsAsNotFound() {
        FakeApi api = FakeApi.forDelete(Response.success(null));
        AtomicReference<Boolean> success = new AtomicReference<>(false);
        new RetrofitReminderRepository(api).delete("conversation-1", "r-1",
                new ReminderRepository.DeleteCallback() {
                    @Override
                    public void onSuccess() {
                        success.set(true);
                    }

                    @Override
                    public void onFailure(ReminderRepository.Failure failure) {
                    }
                });
        assertTrue(success.get());

        FakeApi missing = FakeApi.forDeleteError(Response.error(404, ResponseBody.create(
                MediaType.parse("application/json"),
                "{\"error\":{\"code\":\"NOT_FOUND\"}}")));
        AtomicReference<ReminderRepository.Failure> failure = new AtomicReference<>();
        new RetrofitReminderRepository(missing).delete("conversation-1", "r-1",
                new ReminderRepository.DeleteCallback() {
                    @Override
                    public void onSuccess() {
                    }

                    @Override
                    public void onFailure(ReminderRepository.Failure value) {
                        failure.set(value);
                    }
                });
        assertEquals(ReminderRepository.Failure.NOT_FOUND, failure.get());
    }

    @Test
    public void transportsIOExceptionAsOffline() {
        FakeApi api = FakeApi.forFailure(new IOException("private detail"));
        AtomicReference<ReminderRepository.Failure> failure = new AtomicReference<>();
        new RetrofitReminderRepository(api).load("conversation-1",
                new ReminderRepository.ListCallback() {
                    @Override
                    public void onSuccess(List<Reminder> reminders) {
                    }

                    @Override
                    public void onFailure(ReminderRepository.Failure value) {
                        failure.set(value);
                    }
                });
        assertEquals(ReminderRepository.Failure.OFFLINE, failure.get());
    }

    private static ReminderRepository.ItemCallback itemCallback(
            AtomicReference<Reminder> received) {
        return new ReminderRepository.ItemCallback() {
            @Override
            public void onSuccess(Reminder reminder) {
                received.set(reminder);
            }

            @Override
            public void onFailure(ReminderRepository.Failure failure) {
            }
        };
    }

    private static ReminderRepository.Failure mapFailure(int status, String body) {
        return RetrofitReminderRepository.mapFailure(status, body == null
                ? null
                : ResponseBody.create(MediaType.parse("application/json"), body));
    }

    private static ReminderDto dto(String id, String remindAt, String repeat) {
        return new ReminderDto(
                id, "conversation-1", "Họp team", remindAt, repeat,
                "user-1", "Binh", true,
                "2026-11-09T09:00:00Z", "2026-11-09T09:00:00Z");
    }

    private static final class FakeApi implements ReminderApi {
        private final Response<List<ReminderDto>> listResponse;
        private final Response<ReminderDto> itemResponse;
        private final Response<Void> deleteResponse;
        private final IOException failure;
        private String conversationId;
        private String idempotencyKey;
        private ReminderRequest request;
        private String reminderId;

        private FakeApi(
                Response<List<ReminderDto>> listResponse,
                Response<ReminderDto> itemResponse,
                Response<Void> deleteResponse,
                IOException failure) {
            this.listResponse = listResponse;
            this.itemResponse = itemResponse;
            this.deleteResponse = deleteResponse;
            this.failure = failure;
        }

        static FakeApi forList(Response<List<ReminderDto>> response) {
            return new FakeApi(response, null, null, null);
        }

        static FakeApi forItem(Response<ReminderDto> response) {
            return new FakeApi(null, response, null, null);
        }

        static FakeApi forDelete(Response<Void> response) {
            return new FakeApi(null, null, response, null);
        }

        static FakeApi forDeleteError(Response<Void> response) {
            return new FakeApi(null, null, response, null);
        }

        static FakeApi forFailure(IOException failure) {
            return new FakeApi(null, null, null, failure);
        }

        @Override
        public Call<List<ReminderDto>> list(String conversationId) {
            this.conversationId = conversationId;
            return new FakeCall<>(listResponse, failure);
        }

        @Override
        public Call<ReminderDto> create(
                String conversationId, String idempotencyKey, ReminderRequest request) {
            this.conversationId = conversationId;
            this.idempotencyKey = idempotencyKey;
            this.request = request;
            return new FakeCall<>(itemResponse, failure);
        }

        @Override
        public Call<ReminderDto> update(
                String conversationId, String reminderId, ReminderRequest request) {
            this.conversationId = conversationId;
            this.reminderId = reminderId;
            this.request = request;
            return new FakeCall<>(itemResponse, failure);
        }

        @Override
        public Call<Void> delete(String conversationId, String reminderId) {
            this.conversationId = conversationId;
            this.reminderId = reminderId;
            return new FakeCall<>(deleteResponse, failure);
        }
    }

    private static final class FakeCall<T> implements Call<T> {
        private final Response<T> response;
        private final IOException failure;
        private boolean executed;
        private boolean cancelled;

        private FakeCall(Response<T> response, IOException failure) {
            this.response = response;
            this.failure = failure;
        }

        @Override
        public Response<T> execute() throws IOException {
            executed = true;
            if (failure != null) {
                throw failure;
            }
            return response;
        }

        @Override
        public void enqueue(Callback<T> callback) {
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
        @Override public Call<T> clone() { return new FakeCall<>(response, failure); }
        @Override public Request request() {
            return new Request.Builder().url("https://example.test").build();
        }
        @Override public Timeout timeout() { return Timeout.NONE; }
    }
}
