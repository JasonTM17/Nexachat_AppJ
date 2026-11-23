package com.nexachat.feature.scheduled;

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

public final class ScheduledMessageRepositoryTest {

    @Test
    public void listsInSendAtOrderAndSkipsMalformedRows() {
        ScheduledMessageDto later = dto("later", "SENT", "2026-12-02T02:00:00Z", "");
        ScheduledMessageDto earlier = dto("earlier", "PENDING", "2026-12-01T02:00:00Z", "Sáng mai");
        ScheduledMessageDto broken = new ScheduledMessageDto(
                "broken", "conversation-1", "cm-3", "x", "not-a-time", "PENDING",
                null, null, null, null);
        ScheduledMessageDto unknownStatus = new ScheduledMessageDto(
                "odd", "conversation-1", "cm-4", "x", "2026-12-01T02:00:00Z",
                "QUEUED", null, null, null, null);
        FakeApi api = FakeApi.forList(
                Response.success(List.of(later, broken, unknownStatus, earlier)));

        AtomicReference<List<ScheduledMessage>> received = new AtomicReference<>();
        new RetrofitScheduledMessageRepository(api).load("conversation-1",
                new ScheduledMessageRepository.ListCallback() {
                    @Override
                    public void onSuccess(List<ScheduledMessage> messages) {
                        received.set(messages);
                    }

                    @Override
                    public void onFailure(ScheduledMessageRepository.Failure failure) {
                    }
                });

        assertEquals(2, received.get().size());
        assertEquals("earlier", received.get().get(0).getId());
        assertEquals(ScheduledMessageStatus.PENDING, received.get().get(0).getStatus());
        assertTrue(received.get().get(0).isCancelable());
        assertEquals("later", received.get().get(1).getId());
        assertEquals("conversation-1", api.conversationId);
    }

    @Test
    public void dtoAcceptsClearedTerminalBodies() {
        ScheduledMessageDto sent = new ScheduledMessageDto(
                "s-1", "conversation-1", "cm-1", "",
                "2026-12-01T02:00:00Z", "SENT", null, "m-99",
                "2026-11-30T02:00:00Z", "2026-12-01T02:00:01Z");
        ScheduledMessage domain = sent.toDomain();
        assertEquals("", domain.getBody());
        assertEquals(ScheduledMessageStatus.SENT, domain.getStatus());
        assertEquals("m-99", domain.getSentMessageId());
        assertEquals(Instant.parse("2026-12-01T02:00:01Z"), domain.getUpdatedAt());
    }

    @Test
    public void createsWithClientMessageIdAndUtcIsoSendAt() {
        FakeApi api = FakeApi.forItem(Response.success(dto(
                "created", "PENDING", "2026-12-01T02:00:00Z", "Họp team")));
        RetrofitScheduledMessageRepository repository =
                new RetrofitScheduledMessageRepository(api);
        ScheduledMessageDraft draft = new ScheduledMessageDraft(
                "  Họp team  ", Instant.parse("2026-12-01T02:00:00.123Z"));

        AtomicReference<ScheduledMessage> received = new AtomicReference<>();
        repository.create("conversation-1", draft, "cm-key-1", itemCallback(received));

        assertEquals("cm-key-1", api.request.getClientMessageId());
        assertEquals("Họp team", api.request.getBody());
        assertEquals("2026-12-01T02:00:00Z", api.request.getSendAt());
        assertEquals("created", received.get().getId());
    }

    @Test
    public void createReplaysThePendingRowReturnedByTheServer() {
        FakeApi api = FakeApi.forItem(Response.success(dto(
                "existing", "PENDING", "2026-12-01T02:05:00Z", "again")));
        AtomicReference<ScheduledMessage> received = new AtomicReference<>();
        new RetrofitScheduledMessageRepository(api).create("conversation-1",
                new ScheduledMessageDraft("again", Instant.parse("2026-12-01T02:05:00Z")),
                "cm-key-1", itemCallback(received));
        assertEquals("existing", received.get().getId());
    }

    @Test
    public void mapsServerFailuresWithoutExposingMessages() {
        assertEquals(ScheduledMessageRepository.Failure.NOT_MEMBER,
                mapFailure(403, "{\"error\":{\"code\":\"NOT_MEMBER\"}}"));
        assertEquals(ScheduledMessageRepository.Failure.E2EE,
                mapFailure(409, "{\"error\":{\"code\":\"E2EE_REQUIRED\"}}"));
        assertEquals(ScheduledMessageRepository.Failure.E2EE,
                mapFailure(409, "{\"error\":{\"code\":\"E2EE_NOT_ENABLED\"}}"));
        assertEquals(ScheduledMessageRepository.Failure.LIMIT,
                mapFailure(409, "{\"error\":{\"code\":\"SCHEDULED_LIMIT\"}}"));
        assertEquals(ScheduledMessageRepository.Failure.CONFLICT,
                mapFailure(409, "{\"error\":{\"code\":\"SCHEDULED_INVALID\"}}"));
        assertEquals(ScheduledMessageRepository.Failure.DISPATCHING,
                mapFailure(409, "{\"error\":{\"code\":\"DISPATCHING\","
                        + "\"details\":{\"status\":\"SENT\"}}}"));
        assertEquals(ScheduledMessageRepository.Failure.VALIDATION,
                mapFailure(400, "{\"error\":{\"code\":\"VALIDATION_FAILED\"}}"));
        assertEquals(ScheduledMessageRepository.Failure.VALIDATION, mapFailure(400, null));
        assertEquals(ScheduledMessageRepository.Failure.RATE_LIMITED, mapFailure(429, null));
        assertEquals(ScheduledMessageRepository.Failure.FORBIDDEN,
                mapFailure(403, "{\"error\":{\"code\":\"FORBIDDEN\"}}"));
        assertEquals(ScheduledMessageRepository.Failure.NOT_FOUND, mapFailure(404, null));
        assertEquals(ScheduledMessageRepository.Failure.CONFLICT, mapFailure(409, null));
        assertEquals(ScheduledMessageRepository.Failure.UNKNOWN, mapFailure(500, null));
    }

    @Test
    public void deleteTreats204AsSuccessAndMapsTerminalStates() {
        FakeApi api = FakeApi.forDelete(Response.success(null));
        AtomicReference<Boolean> success = new AtomicReference<>(false);
        new RetrofitScheduledMessageRepository(api).delete("conversation-1", "s-1",
                new ScheduledMessageRepository.DeleteCallback() {
                    @Override
                    public void onSuccess() {
                        success.set(true);
                    }

                    @Override
                    public void onFailure(ScheduledMessageRepository.Failure failure) {
                    }
                });
        assertTrue(success.get());
        assertEquals("s-1", api.scheduledId);

        FakeApi dispatching = FakeApi.forDelete(Response.error(409, ResponseBody.create(
                MediaType.parse("application/json"),
                "{\"error\":{\"code\":\"DISPATCHING\",\"details\":{\"status\":\"SENT\"}}}")));
        AtomicReference<ScheduledMessageRepository.Failure> failure = new AtomicReference<>();
        new RetrofitScheduledMessageRepository(dispatching).delete("conversation-1", "s-1",
                new ScheduledMessageRepository.DeleteCallback() {
                    @Override
                    public void onSuccess() {
                    }

                    @Override
                    public void onFailure(ScheduledMessageRepository.Failure value) {
                        failure.set(value);
                    }
                });
        assertEquals(ScheduledMessageRepository.Failure.DISPATCHING, failure.get());
    }

    @Test
    public void transportsIOExceptionAsOffline() {
        FakeApi api = FakeApi.forFailure(new IOException("private detail"));
        AtomicReference<ScheduledMessageRepository.Failure> failure = new AtomicReference<>();
        new RetrofitScheduledMessageRepository(api).load("conversation-1",
                new ScheduledMessageRepository.ListCallback() {
                    @Override
                    public void onSuccess(List<ScheduledMessage> messages) {
                    }

                    @Override
                    public void onFailure(ScheduledMessageRepository.Failure value) {
                        failure.set(value);
                    }
                });
        assertEquals(ScheduledMessageRepository.Failure.OFFLINE, failure.get());
    }

    private static ScheduledMessageRepository.ItemCallback itemCallback(
            AtomicReference<ScheduledMessage> received) {
        return new ScheduledMessageRepository.ItemCallback() {
            @Override
            public void onSuccess(ScheduledMessage message) {
                received.set(message);
            }

            @Override
            public void onFailure(ScheduledMessageRepository.Failure failure) {
            }
        };
    }

    private static ScheduledMessageRepository.Failure mapFailure(int status, String body) {
        return RetrofitScheduledMessageRepository.mapFailure(status, body == null
                ? null
                : ResponseBody.create(MediaType.parse("application/json"), body));
    }

    private static ScheduledMessageDto dto(
            String id, String status, String sendAt, String body) {
        return new ScheduledMessageDto(
                id, "conversation-1", "cm-" + id, body, sendAt, status,
                "FAILED".equals(status) ? "FAILED_RETRY_EXHAUSTED" : null, null,
                "2026-11-09T09:00:00Z", "2026-11-09T09:00:00Z");
    }

    private static final class FakeApi implements ScheduledMessageApi {
        private final Response<List<ScheduledMessageDto>> listResponse;
        private final Response<ScheduledMessageDto> itemResponse;
        private final Response<Void> deleteResponse;
        private final IOException failure;
        private String conversationId;
        private ScheduledMessageRequest request;
        private String scheduledId;

        private FakeApi(
                Response<List<ScheduledMessageDto>> listResponse,
                Response<ScheduledMessageDto> itemResponse,
                Response<Void> deleteResponse,
                IOException failure) {
            this.listResponse = listResponse;
            this.itemResponse = itemResponse;
            this.deleteResponse = deleteResponse;
            this.failure = failure;
        }

        static FakeApi forList(Response<List<ScheduledMessageDto>> response) {
            return new FakeApi(response, null, null, null);
        }

        static FakeApi forItem(Response<ScheduledMessageDto> response) {
            return new FakeApi(null, response, null, null);
        }

        static FakeApi forDelete(Response<Void> response) {
            return new FakeApi(null, null, response, null);
        }

        static FakeApi forFailure(IOException failure) {
            return new FakeApi(null, null, null, failure);
        }

        @Override
        public Call<List<ScheduledMessageDto>> list(String conversationId) {
            this.conversationId = conversationId;
            return new FakeCall<>(listResponse, failure);
        }

        @Override
        public Call<ScheduledMessageDto> create(
                String conversationId, ScheduledMessageRequest request) {
            this.conversationId = conversationId;
            this.request = request;
            return new FakeCall<>(itemResponse, failure);
        }

        @Override
        public Call<Void> delete(String conversationId, String scheduledId) {
            this.conversationId = conversationId;
            this.scheduledId = scheduledId;
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
