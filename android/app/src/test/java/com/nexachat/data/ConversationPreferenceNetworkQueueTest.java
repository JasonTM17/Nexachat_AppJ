package com.nexachat.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nexachat.core.common.Result;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.Before;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;

public final class ConversationPreferenceNetworkQueueTest {

    private ConversationPreferenceLanes lanes;
    private ConversationPreferenceNetworkQueue queue;
    private final AtomicInteger concurrent = new AtomicInteger();
    private final List<Boolean> archiveRequests = new CopyOnWriteArrayList<>();

    @Before
    public void setUp() {
        lanes = new ConversationPreferenceLanes();
        concurrent.set(0);
        archiveRequests.clear();
        queue = new ConversationPreferenceNetworkQueue(
                call -> Single.defer(() -> {
                    int active = concurrent.incrementAndGet();
                    try {
                        assertEquals(1, active);
                        Response<Void> response = call.execute();
                        return Single.just(response.isSuccessful()
                                ? new Result.Success<>(null)
                                : Result.failure(
                                        new com.nexachat.core.common.ApiError.Network("fail")));
                    } finally {
                        concurrent.decrementAndGet();
                    }
                }),
                new ConversationPreferenceNetworkQueue.OutcomeHandler() {
                    @Override
                    public Single<Result<Void>> onArchiveNetworkComplete(
                            String conversationId,
                            long version,
                            boolean attemptedArchived,
                            Result<Void> result) {
                        return resolveLane(
                                conversationId,
                                ConversationPreferenceLanes.Kind.ARCHIVE,
                                version,
                                result);
                    }

                    @Override
                    public Single<Result<Void>> onMuteNetworkComplete(
                            String conversationId,
                            long version,
                            ConversationPreferenceNetworkQueue.MuteState attempted,
                            Result<Void> result) {
                        return resolveLane(
                                conversationId,
                                ConversationPreferenceLanes.Kind.MUTE,
                                version,
                                result);
                    }

                    @Override
                    public Single<Result<Void>> onPinNetworkComplete(
                            String conversationId,
                            long version,
                            ConversationPreferenceNetworkQueue.PinState attempted,
                            Result<Void> result) {
                        return resolveLane(
                                conversationId,
                                ConversationPreferenceLanes.Kind.PIN,
                                version,
                                result);
                    }
                },
                lanes);
        queue.seedArchiveIfUnknown("c-1", false);
    }

    /**
     * Mirrors {@code ConversationRepository.resolveOrderedPreferenceResult}
     * minus the Room rollback: a stale completion settles to a synthetic
     * success; a fresh completion passes its real result through.
     */
    private Single<Result<Void>> resolveLane(
            String conversationId,
            ConversationPreferenceLanes.Kind kind,
            long version,
            Result<Void> result) {
        if (lanes.isStale(conversationId, kind, version)) {
            lanes.finish(conversationId, kind, version);
            return Single.just(new Result.Success<>(null));
        }
        lanes.finish(conversationId, kind, version);
        return Single.just(result);
    }

    @Test
    public void archiveUndoArchiveCoalescesWhileFirstArchiveInFlight() throws Exception {
        HoldArchiveCall held = new HoldArchiveCall(new ArrayList<>());
        Function<Boolean, Call<Void>> calls = archived -> {
            archiveRequests.add(archived);
            return archived ? held : new ImmediateVoidCall(false);
        };
        ConversationPreferenceLanes.Ticket first =
                lanes.begin("c-1", ConversationPreferenceLanes.Kind.ARCHIVE);
        Thread flight = new Thread(
                () -> queue.submitArchive("c-1", first, true, calls).blockingGet(),
                "archive-flight");
        flight.start();
        held.awaitExecuteReached();
        ConversationPreferenceLanes.Ticket undo =
                lanes.begin("c-1", ConversationPreferenceLanes.Kind.ARCHIVE);
        queue.submitArchive("c-1", undo, false, calls).blockingGet();
        ConversationPreferenceLanes.Ticket again =
                lanes.begin("c-1", ConversationPreferenceLanes.Kind.ARCHIVE);
        queue.submitArchive("c-1", again, true, calls).blockingGet();

        held.completeSuccess();
        flight.join(5_000);

        assertEquals(List.of(true), archiveRequests);
        assertTrue(queue.confirmedArchived("c-1"));
    }

    @Test
    public void archiveUndoWhileInFlightWaitsForFirstRequest() throws Exception {
        List<String> order = new ArrayList<>();
        HoldArchiveCall held = new HoldArchiveCall(order);
        Function<Boolean, Call<Void>> calls = archived -> {
            if (archived) return held;
            order.add("unarchive-http");
            return new ImmediateVoidCall(false);
        };

        ConversationPreferenceLanes.Ticket archiveTicket =
                lanes.begin("c-1", ConversationPreferenceLanes.Kind.ARCHIVE);
        Single<Result<Void>> first =
                queue.submitArchive("c-1", archiveTicket, true, calls);
        Thread archiveThread = new Thread(first::blockingGet, "archive-flight");
        archiveThread.start();
        held.awaitExecuteReached();
        assertTrue(order.isEmpty());

        ConversationPreferenceLanes.Ticket undoTicket =
                lanes.begin("c-1", ConversationPreferenceLanes.Kind.ARCHIVE);
        assertTrue(queue.submitArchive("c-1", undoTicket, false, calls).blockingGet()
                instanceof Result.Success<?>);
        assertEquals(List.of(), order);

        held.completeSuccess();
        try {
            archiveThread.join(5_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }

        assertEquals(List.of("archive-http", "unarchive-http"), order);
        assertFalse(queue.confirmedArchived("c-1"));
        assertFalse(lanes.preserveLocalOverServer(
                "c-1", ConversationPreferenceLanes.Kind.ARCHIVE));
    }

    @Test
    public void archiveNoopMatchingConfirmedStillFinishesTheLane() {
        Function<Boolean, Call<Void>> calls = archived -> {
            archiveRequests.add(archived);
            return new ImmediateVoidCall(archived);
        };
        ConversationPreferenceLanes.Ticket ticket =
                lanes.begin("c-1", ConversationPreferenceLanes.Kind.ARCHIVE);

        assertTrue(queue.submitArchive("c-1", ticket, false, calls).blockingGet()
                instanceof Result.Success<?>);

        assertTrue(archiveRequests.isEmpty());
        assertFalse(lanes.preserveLocalOverServer(
                "c-1", ConversationPreferenceLanes.Kind.ARCHIVE));
        queue.noteServerArchive("c-1", true);
        assertTrue(queue.confirmedArchived("c-1"));
    }

    @Test
    public void muteNoopMatchingConfirmedStillFinishesTheLane() {
        queue.seedMuteIfUnknown("c-1", false, null);
        List<ConversationPreferenceNetworkQueue.MuteState> requests = new ArrayList<>();
        Function<ConversationPreferenceNetworkQueue.MuteState, Call<Void>> calls =
                state -> {
                    requests.add(state);
                    return new ImmediateVoidCall(false);
                };
        ConversationPreferenceLanes.Ticket ticket =
                lanes.begin("c-1", ConversationPreferenceLanes.Kind.MUTE);

        assertTrue(queue.submitMute(
                        "c-1",
                        ticket,
                        new ConversationPreferenceNetworkQueue.MuteState(false, null),
                        calls)
                .blockingGet() instanceof Result.Success<?>);

        assertTrue(requests.isEmpty());
        assertFalse(lanes.preserveLocalOverServer(
                "c-1", ConversationPreferenceLanes.Kind.MUTE));
        queue.noteServerMute("c-1", true, 42L);
        assertEquals(
                new ConversationPreferenceNetworkQueue.MuteState(true, 42L),
                queue.confirmedMute("c-1"));
    }

    @Test
    public void pinNoopMatchingConfirmedStillFinishesTheLane() {
        queue.seedPinIfUnknown("c-1", false, null);
        List<ConversationPreferenceNetworkQueue.PinState> requests = new ArrayList<>();
        Function<ConversationPreferenceNetworkQueue.PinState, Call<Void>> calls =
                state -> {
                    requests.add(state);
                    return new ImmediateVoidCall(false);
                };
        ConversationPreferenceLanes.Ticket ticket =
                lanes.begin("c-1", ConversationPreferenceLanes.Kind.PIN);

        assertTrue(queue.submitPin(
                        "c-1",
                        ticket,
                        new ConversationPreferenceNetworkQueue.PinState(false, null),
                        calls)
                .blockingGet() instanceof Result.Success<?>);

        assertTrue(requests.isEmpty());
        assertFalse(lanes.preserveLocalOverServer(
                "c-1", ConversationPreferenceLanes.Kind.PIN));
        queue.noteServerPin("c-1", true, 7L);
        assertEquals(
                new ConversationPreferenceNetworkQueue.PinState(true, 7L),
                queue.confirmedPin("c-1"));
    }

    @Test
    public void supersededPendingTicketStillFinishesTheArchiveLane() throws Exception {
        List<String> order = new ArrayList<>();
        HoldArchiveCall held = new HoldArchiveCall(order);
        Function<Boolean, Call<Void>> calls = archived -> {
            archiveRequests.add(archived);
            return archived ? held : new ImmediateVoidCall(archived);
        };
        ConversationPreferenceLanes.Ticket first =
                lanes.begin("c-1", ConversationPreferenceLanes.Kind.ARCHIVE);
        Thread flight = new Thread(
                () -> queue.submitArchive("c-1", first, true, calls).blockingGet(),
                "archive-flight");
        flight.start();
        held.awaitExecuteReached();

        ConversationPreferenceLanes.Ticket superseded =
                lanes.begin("c-1", ConversationPreferenceLanes.Kind.ARCHIVE);
        queue.submitArchive("c-1", superseded, false, calls).blockingGet();
        ConversationPreferenceLanes.Ticket latest =
                lanes.begin("c-1", ConversationPreferenceLanes.Kind.ARCHIVE);
        queue.submitArchive("c-1", latest, true, calls).blockingGet();

        held.completeSuccess();
        flight.join(5_000);

        assertEquals(List.of(true), archiveRequests);
        assertTrue(queue.confirmedArchived("c-1"));
        assertFalse(lanes.preserveLocalOverServer(
                "c-1", ConversationPreferenceLanes.Kind.ARCHIVE));
    }

    @Test
    public void staleFailedArchiveFlightDoesNotConfirmOrSkipFollowUp() throws Exception {
        HoldArchiveCall heldFail = new HoldArchiveCall(new ArrayList<>(), true);
        Function<Boolean, Call<Void>> calls = archived -> {
            archiveRequests.add(archived);
            return archiveRequests.size() == 1 ? heldFail : new ImmediateVoidCall(archived);
        };
        ConversationPreferenceLanes.Ticket first =
                lanes.begin("c-1", ConversationPreferenceLanes.Kind.ARCHIVE);
        Thread flight = new Thread(
                () -> queue.submitArchive("c-1", first, true, calls).blockingGet(),
                "archive-fail-flight");
        flight.start();
        heldFail.awaitExecuteReached();

        ConversationPreferenceLanes.Ticket again =
                lanes.begin("c-1", ConversationPreferenceLanes.Kind.ARCHIVE);
        queue.submitArchive("c-1", again, true, calls).blockingGet();

        heldFail.completeSuccess();
        flight.join(5_000);

        // The stale failure must not count as confirmed; the pending intent
        // still needs its own request because the server never archived.
        assertEquals(List.of(true, true), archiveRequests);
        assertTrue(queue.confirmedArchived("c-1"));
        assertFalse(lanes.preserveLocalOverServer(
                "c-1", ConversationPreferenceLanes.Kind.ARCHIVE));
        queue.noteServerArchive("c-1", false);
        assertFalse(queue.confirmedArchived("c-1"));
    }

    @Test
    public void staleFailedMuteFlightDoesNotConfirmOrSkipFollowUp() throws Exception {
        queue.seedMuteIfUnknown("c-1", false, null);
        List<ConversationPreferenceNetworkQueue.MuteState> requests = new ArrayList<>();
        HoldArchiveCall heldFail = new HoldArchiveCall(new ArrayList<>(), true);
        ConversationPreferenceNetworkQueue.MuteState mutedForever =
                new ConversationPreferenceNetworkQueue.MuteState(true, null);
        Function<ConversationPreferenceNetworkQueue.MuteState, Call<Void>> calls =
                state -> {
                    requests.add(state);
                    return requests.size() == 1 ? heldFail : new ImmediateVoidCall(false);
                };
        ConversationPreferenceLanes.Ticket first =
                lanes.begin("c-1", ConversationPreferenceLanes.Kind.MUTE);
        Thread flight = new Thread(
                () -> queue.submitMute("c-1", first, mutedForever, calls).blockingGet(),
                "mute-fail-flight");
        flight.start();
        heldFail.awaitExecuteReached();

        ConversationPreferenceLanes.Ticket again =
                lanes.begin("c-1", ConversationPreferenceLanes.Kind.MUTE);
        queue.submitMute("c-1", again, mutedForever, calls).blockingGet();

        heldFail.completeSuccess();
        flight.join(5_000);

        assertEquals(
                List.of(mutedForever, mutedForever), requests);
        assertEquals(mutedForever, queue.confirmedMute("c-1"));
        assertFalse(lanes.preserveLocalOverServer(
                "c-1", ConversationPreferenceLanes.Kind.MUTE));
        queue.noteServerMute("c-1", false, null);
        assertEquals(
                new ConversationPreferenceNetworkQueue.MuteState(false, null),
                queue.confirmedMute("c-1"));
    }

    @Test
    public void staleFailedPinFlightDoesNotConfirmOrSkipFollowUp() throws Exception {
        queue.seedPinIfUnknown("c-1", false, null);
        List<ConversationPreferenceNetworkQueue.PinState> requests = new ArrayList<>();
        HoldArchiveCall heldFail = new HoldArchiveCall(new ArrayList<>(), true);
        ConversationPreferenceNetworkQueue.PinState pinned =
                new ConversationPreferenceNetworkQueue.PinState(true, 100L);
        Function<ConversationPreferenceNetworkQueue.PinState, Call<Void>> calls =
                state -> {
                    requests.add(state);
                    return requests.size() == 1 ? heldFail : new ImmediateVoidCall(false);
                };
        ConversationPreferenceLanes.Ticket first =
                lanes.begin("c-1", ConversationPreferenceLanes.Kind.PIN);
        Thread flight = new Thread(
                () -> queue.submitPin("c-1", first, pinned, calls).blockingGet(),
                "pin-fail-flight");
        flight.start();
        heldFail.awaitExecuteReached();

        ConversationPreferenceLanes.Ticket again =
                lanes.begin("c-1", ConversationPreferenceLanes.Kind.PIN);
        queue.submitPin("c-1", again, pinned, calls).blockingGet();

        heldFail.completeSuccess();
        flight.join(5_000);

        assertEquals(List.of(pinned, pinned), requests);
        assertEquals(pinned, queue.confirmedPin("c-1"));
        assertFalse(lanes.preserveLocalOverServer(
                "c-1", ConversationPreferenceLanes.Kind.PIN));
        queue.noteServerPin("c-1", false, null);
        assertEquals(
                new ConversationPreferenceNetworkQueue.PinState(false, null),
                queue.confirmedPin("c-1"));
    }

    private static final class ImmediateVoidCall implements Call<Void> {
        private final boolean archived;

        ImmediateVoidCall(boolean archived) {
            this.archived = archived;
        }

        @Override
        public Response<Void> execute() {
            return Response.success(204, null);
        }

        @Override
        public void enqueue(retrofit2.Callback<Void> callback) {
            callback.onResponse(this, execute());
        }

        @Override
        public boolean isExecuted() {
            return true;
        }

        @Override
        public void cancel() {
        }

        @Override
        public boolean isCanceled() {
            return false;
        }

        @Override
        public Call<Void> clone() {
            return this;
        }

        @Override
        public okhttp3.Request request() {
            return new okhttp3.Request.Builder().url("https://example.invalid/" + archived).build();
        }

        @Override
        public okio.Timeout timeout() {
            return okio.Timeout.NONE;
        }
    }

    private static final class HoldArchiveCall implements Call<Void> {
        private final List<String> order;
        private final boolean fails;
        private final java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch reachedExecute =
                new java.util.concurrent.CountDownLatch(1);

        HoldArchiveCall(List<String> order) {
            this(order, false);
        }

        HoldArchiveCall(List<String> order, boolean fails) {
            this.order = order;
            this.fails = fails;
        }

        void awaitExecuteReached() throws InterruptedException {
            assertTrue(reachedExecute.await(5, java.util.concurrent.TimeUnit.SECONDS));
        }

        void completeSuccess() {
            release.countDown();
        }

        @Override
        public Response<Void> execute() throws java.io.IOException {
            reachedExecute.countDown();
            try {
                if (!release.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new java.io.IOException("timeout");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException(interrupted);
            }
            order.add("archive-http");
            if (fails) {
                return Response.error(500, okhttp3.ResponseBody.create(
                        okhttp3.MediaType.parse("text/plain"), "boom"));
            }
            return Response.success(204, null);
        }

        @Override
        public void enqueue(retrofit2.Callback<Void> callback) {
            try {
                callback.onResponse(this, execute());
            } catch (java.io.IOException failure) {
                callback.onFailure(this, failure);
            }
        }

        @Override
        public boolean isExecuted() {
            return false;
        }

        @Override
        public void cancel() {
            release.countDown();
        }

        @Override
        public boolean isCanceled() {
            return false;
        }

        @Override
        public Call<Void> clone() {
            return this;
        }

        @Override
        public okhttp3.Request request() {
            return new okhttp3.Request.Builder().url("https://example.invalid/hold").build();
        }

        @Override
        public okio.Timeout timeout() {
            return okio.Timeout.NONE;
        }
    }
}
