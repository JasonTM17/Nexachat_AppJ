package com.nexachat.feature.poll;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
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

public final class PollRepositoryTest {

    @Test
    public void listsTheFirstPageWithTheDefaultLimitAndMapsAnonymousPrivacy() {
        PollOptionDto option = new PollOptionDto(
                "o-1", 0, "Biển", 3, List.of("u-1", "u-2", "u-3"));
        PollDto poll = poll("p-1", true, List.of(option), List.of("o-1"), 4L);
        FakeApi api = FakeApi.forList(Response.success(
                new PollPageDto(List.of(poll), "cursor-1")));

        AtomicReference<PollPage> received = new AtomicReference<>();
        new RetrofitPollRepository(api).load(
                "conversation-1", null, new PollRepository.PageCallback() {
                    @Override
                    public void onSuccess(PollPage page) {
                        received.set(page);
                    }

                    @Override
                    public void onFailure(PollRepository.Failure failure) {
                    }
                });

        assertEquals("conversation-1", api.conversationId);
        assertEquals(RetrofitPollRepository.PAGE_SIZE, api.limit);
        assertEquals(1, received.get().getItems().size());
        Poll mapped = received.get().getItems().get(0);
        assertTrue(mapped.isAnonymous());
        assertTrue(mapped.getOptions().get(0).getVoterIds().isEmpty());
        assertEquals(3, mapped.getOptions().get(0).getVoteCount());
        assertEquals("cursor-1", received.get().getNextBefore());
        assertTrue(received.get().hasMore());
    }

    @Test
    public void mapsTheChangeFeedIncludingDeleteTombstones() {
        PollChangesDto.ChangeDto updated = new PollChangesDto.ChangeDto(
                "p-1", 5L, false, poll("p-1", false, List.of(
                        new PollOptionDto("o-1", 0, "Biển", 1, List.of("u-1"))),
                        List.of("o-1"), 5L));
        PollChangesDto.ChangeDto deleted = new PollChangesDto.ChangeDto(
                "p-2", 6L, true, null);
        FakeApi api = FakeApi.forChanges(Response.success(
                new PollChangesDto(List.of(updated, deleted), 6L, false)));

        AtomicReference<PollChanges> received = new AtomicReference<>();
        new RetrofitPollRepository(api).changes(
                "conversation-1", 4L, 100, new PollRepository.ChangesCallback() {
                    @Override
                    public void onSuccess(PollChanges changes) {
                        received.set(changes);
                    }

                    @Override
                    public void onFailure(PollRepository.Failure failure) {
                    }
                });

        assertEquals(4L, api.since);
        assertEquals(100, api.limit);
        assertEquals(2, received.get().getItems().size());
        assertFalse(received.get().getItems().get(0).isDeleted());
        assertEquals(5L, received.get().getItems().get(0).getVersion());
        assertTrue(received.get().getItems().get(1).isDeleted());
        assertEquals(6L, received.get().getNextSince());
        assertFalse(received.get().hasMore());
    }

    @Test
    public void createsWithNormalizedFieldsAndTheOptionalExpiry() {
        FakeApi api = FakeApi.forItem(Response.success(
                poll("p-1", false, List.of(), List.of(), 1L)));
        PollDraft draft = new PollDraft(
                "  Đi đâu? ", List.of(" Biển ", "Núi"),
                true, false, java.time.Instant.parse("2026-12-01T16:59:59Z"));

        new RetrofitPollRepository(api).create("conversation-1", draft,
                new PollRepository.ItemCallback() {
                    @Override
                    public void onSuccess(Poll poll) {
                    }

                    @Override
                    public void onFailure(PollRepository.Failure failure) {
                    }
                });

        assertEquals("Đi đâu?", api.createRequest.getQuestion());
        assertEquals(List.of("Biển", "Núi"), api.createRequest.getOptions());
        assertEquals(Boolean.TRUE, api.createRequest.getMultipleChoice());
        assertEquals(Boolean.FALSE, api.createRequest.getAnonymous());
        assertEquals("2026-12-01T16:59:59Z", api.createRequest.getClosesAt());
    }

    @Test
    public void votesWithTheCompleteSelection() {
        FakeApi api = FakeApi.forItem(Response.success(
                poll("p-1", false, List.of(), List.of(), 2L)));

        new RetrofitPollRepository(api).vote(
                "conversation-1", "p-1", List.of("o-1", "o-2"),
                new PollRepository.ItemCallback() {
                    @Override
                    public void onSuccess(Poll poll) {
                    }

                    @Override
                    public void onFailure(PollRepository.Failure failure) {
                    }
                });

        assertEquals(List.of("o-1", "o-2"), api.voteRequest.getOptionIds());
    }

    @Test
    public void mapsTheDocumentedErrorVocabulary() {
        assertEquals(PollRepository.Failure.NOT_A_GROUP,
                mapFailure(400, "{\"error\":{\"code\":\"NOT_A_GROUP\"}}"));
        assertEquals(PollRepository.Failure.NOT_MEMBER,
                mapFailure(403, "{\"error\":{\"code\":\"NOT_MEMBER\"}}"));
        assertEquals(PollRepository.Failure.NOT_ADMIN,
                mapFailure(403, "{\"error\":{\"code\":\"NOT_ADMIN\"}}"));
        assertEquals(PollRepository.Failure.NOT_FOUND,
                mapFailure(404, "{\"error\":{\"code\":\"POLL_NOT_FOUND\"}}"));
        assertEquals(PollRepository.Failure.CLOSED,
                mapFailure(409, "{\"error\":{\"code\":\"POLL_CLOSED\"}}"));
        assertEquals(PollRepository.Failure.VALIDATION,
                mapFailure(400, "{\"error\":{\"code\":\"VALIDATION_FAILED\"}}"));
        assertEquals(PollRepository.Failure.RATE_LIMITED, mapFailure(429, null));
        assertEquals(PollRepository.Failure.UNKNOWN, mapFailure(500, null));
    }

    @Test
    public void deleteTreats204AsSuccessAndIoAsOffline() {
        FakeApi deleting = FakeApi.forDelete(Response.success(null));
        AtomicReference<Boolean> success = new AtomicReference<>(false);
        new RetrofitPollRepository(deleting).delete(
                "conversation-1", "p-1", new PollRepository.DeleteCallback() {
                    @Override
                    public void onSuccess() {
                        success.set(true);
                    }

                    @Override
                    public void onFailure(PollRepository.Failure failure) {
                    }
                });
        assertTrue(success.get());

        FakeApi offline = FakeApi.forFailure(new IOException("private"));
        AtomicReference<PollRepository.Failure> failure = new AtomicReference<>();
        new RetrofitPollRepository(offline).load(
                "conversation-1", null, new PollRepository.PageCallback() {
                    @Override
                    public void onSuccess(PollPage page) {
                    }

                    @Override
                    public void onFailure(PollRepository.Failure value) {
                        failure.set(value);
                    }
                });
        assertEquals(PollRepository.Failure.OFFLINE, failure.get());
    }

    private static PollRepository.Failure mapFailure(int status, String body) {
        return RetrofitPollRepository.mapFailure(status, body == null
                ? null
                : ResponseBody.create(MediaType.parse("application/json"), body));
    }

    private static PollDto poll(
            String id, boolean anonymous, List<PollOptionDto> options,
            List<String> myOptionIds, long version) {
        return new PollDto(
                id, "conversation-1", "creator-1", "Đi đâu?", false, anonymous,
                "OPEN", null, null, null, "2026-11-09T09:00:00Z", version,
                options.size(), options, myOptionIds);
    }

    private static final class FakeApi implements PollApi {
        private final Response<PollPageDto> listResponse;
        private final Response<PollChangesDto> changesResponse;
        private final Response<PollDto> itemResponse;
        private final Response<Void> deleteResponse;
        private final IOException failure;
        private String conversationId;
        private int limit;
        private String before;
        private long since;
        private CreatePollRequest createRequest;
        private VoteRequest voteRequest;

        private FakeApi(
                Response<PollPageDto> listResponse,
                Response<PollChangesDto> changesResponse,
                Response<PollDto> itemResponse,
                Response<Void> deleteResponse,
                IOException failure) {
            this.listResponse = listResponse;
            this.changesResponse = changesResponse;
            this.itemResponse = itemResponse;
            this.deleteResponse = deleteResponse;
            this.failure = failure;
        }

        static FakeApi forList(Response<PollPageDto> response) {
            return new FakeApi(response, null, null, null, null);
        }

        static FakeApi forChanges(Response<PollChangesDto> response) {
            return new FakeApi(null, response, null, null, null);
        }

        static FakeApi forItem(Response<PollDto> response) {
            return new FakeApi(null, null, response, null, null);
        }

        static FakeApi forDelete(Response<Void> response) {
            return new FakeApi(null, null, null, response, null);
        }

        static FakeApi forFailure(IOException failure) {
            return new FakeApi(null, null, null, null, failure);
        }

        @Override
        public Call<PollPageDto> list(String conversationId, int limit, String before) {
            this.conversationId = conversationId;
            this.limit = limit;
            this.before = before;
            return new FakeCall<>(listResponse, failure);
        }

        @Override
        public Call<PollChangesDto> changes(String conversationId, long since, int limit) {
            this.conversationId = conversationId;
            this.since = since;
            this.limit = limit;
            return new FakeCall<>(changesResponse, failure);
        }

        @Override
        public Call<PollDto> create(String conversationId, CreatePollRequest request) {
            this.conversationId = conversationId;
            this.createRequest = request;
            return new FakeCall<>(itemResponse, failure);
        }

        @Override
        public Call<PollDto> vote(
                String conversationId, String pollId, VoteRequest request) {
            this.conversationId = conversationId;
            this.voteRequest = request;
            return new FakeCall<>(itemResponse, failure);
        }

        @Override
        public Call<PollDto> retractVote(String conversationId, String pollId) {
            this.conversationId = conversationId;
            return new FakeCall<>(itemResponse, failure);
        }

        @Override
        public Call<PollDto> close(String conversationId, String pollId) {
            this.conversationId = conversationId;
            return new FakeCall<>(itemResponse, failure);
        }

        @Override
        public Call<Void> delete(String conversationId, String pollId) {
            this.conversationId = conversationId;
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
