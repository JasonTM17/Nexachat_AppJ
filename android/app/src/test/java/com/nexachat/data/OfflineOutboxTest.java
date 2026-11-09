package com.nexachat.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import androidx.work.WorkerParameters;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.database.AppDatabase;
import com.nexachat.core.database.ConversationDao;
import com.nexachat.core.database.MessageDao;
import com.nexachat.core.database.MessageReactionEntity;
import com.nexachat.core.database.OfflineOutboxEntity;
import com.nexachat.core.database.OutboxDao;
import com.nexachat.core.database.OutboxState;
import com.nexachat.core.database.PinDao;
import com.nexachat.core.database.PinnedMessageEntity;
import com.nexachat.core.database.ReactionDao;
import com.nexachat.core.network.ApiResult;
import com.nexachat.core.network.MessageApi;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.dto.ReactionRequest;
import com.nexachat.core.network.dto.ReactionSummaryDto;
import com.nexachat.core.network.dto.ReactionUpdateDto;
import com.nexachat.core.network.stomp.ChatEvent;
import com.nexachat.core.network.stomp.RealtimeSession;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.observers.TestObserver;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.ResponseBody;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Producer, dispatcher, retry, rollback and fallback coverage for the offline outbox. */
public final class OfflineOutboxTest {
    private static final String CONVERSATION_ID = "c-outbox";
    private static final String MESSAGE_ID = "m-outbox";
    private static final String MY_ID = "me-1";

    private Moshi moshi;
    private FakeOfflineOutboxDao dao;
    private AppDatabase appDatabase;
    private RecordingActionApi actionApi;
    private OfflineOutboxScheduler scheduler;
    private MessageRepository repository;
    private io.reactivex.rxjava3.processors.PublishProcessor<ChatEvent> sessionEventStream;
    private io.reactivex.rxjava3.processors.PublishProcessor<SessionEvents.Event> sessionSignalStream;

    @Before public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
        moshi = new Moshi.Builder().build();
        dao = new FakeOfflineOutboxDao();
        appDatabase = mock(AppDatabase.class);
        doAnswer(invocation -> {
            Runnable body = invocation.getArgument(0);
            Object snapshot = dao.snapshot();
            dao.transactionDepth++;
            try {
                body.run();
            } catch (RuntimeException failure) {
                dao.restore(snapshot);
                throw failure;
            } finally {
                dao.transactionDepth--;
            }
            return null;
        }).when(appDatabase).runInTransaction(any(Runnable.class));
        actionApi = new RecordingActionApi();
        scheduler = mock(OfflineOutboxScheduler.class);
        sessionEventStream = io.reactivex.rxjava3.processors.PublishProcessor.create();
        sessionSignalStream = io.reactivex.rxjava3.processors.PublishProcessor.create();
        repository = newRepository(actionApi);
    }

    @After public void tearDown() {
        if (repository != null) repository.dispose();
        RxJavaPlugins.reset();
    }

    // ------------------------------------------------------------------ harness

    private MessageRepository newRepository(OfflineActionApi api) {
        MessageApi messageApi = mock(MessageApi.class);
        MessageDao messageDao = mock(MessageDao.class);
        ConversationDao conversationDao = mock(ConversationDao.class);
        OutboxDao outboxDao = mock(OutboxDao.class);
        ReactionDao reactionDao = mock(ReactionDao.class);
        PinDao pinDao = mock(PinDao.class);
        RealtimeSession session = mock(RealtimeSession.class);
        TokenStore tokenStore = mock(TokenStore.class);
        SessionEvents sessionEvents = mock(SessionEvents.class);
        when(session.getEvents()).thenReturn(sessionEventStream);
        when(session.getResyncRequests()).thenReturn(Flowable.empty());
        when(session.getIsOpen()).thenReturn(
                io.reactivex.rxjava3.processors.BehaviorProcessor.createDefault(true));
        when(sessionEvents.getEvents()).thenReturn(sessionSignalStream);
        when(tokenStore.readTokens()).thenReturn(
                Maybe.just(new Tokens(jwtFor(MY_ID), Long.MAX_VALUE, "refresh")));
        when(messageDao.deleteAll()).thenReturn(Completable.complete());
        when(conversationDao.clear()).thenReturn(Completable.complete());
        when(outboxDao.clear()).thenReturn(Completable.complete());
        when(reactionDao.clear()).thenReturn(Completable.complete());
        when(pinDao.clear()).thenReturn(Completable.complete());
        // Realtime and delta updates share the fake outbox store so reconciliation is observable.
        doAnswer(invocation -> {
            dao.deleteReactionsBlocking(invocation.getArgument(0));
            dao.insertReactionsBlocking(invocation.getArgument(1));
            return Completable.complete();
        }).when(reactionDao).replaceForMessage(anyString(), any());
        doAnswer(invocation -> {
            dao.insertPinBlocking(invocation.getArgument(0));
            return Completable.complete();
        }).when(pinDao).upsert(any(PinnedMessageEntity.class));
        doAnswer(invocation -> {
            dao.deletePinBlocking(invocation.getArgument(0), invocation.getArgument(1));
            return Completable.complete();
        }).when(pinDao).delete(anyString(), anyString());
        return new MessageRepository(messageApi, messageDao, conversationDao, outboxDao, reactionDao,
                pinDao, session, tokenStore, sessionEvents, moshi, appDatabase, dao, api, scheduler);
    }

    private OutboxSyncWorker worker() {
        return new OutboxSyncWorker(mock(Context.class), mock(WorkerParameters.class), repository, dao);
    }

    private OfflineOutboxAction payload(OfflineOutboxEntity row) {
        try {
            return OfflineOutboxAction.fromJson(row.getPayloadJson(), moshi);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private OfflineOutboxEntity row(String id, String actionType, String messageId, String conversationId,
            String emoji, long createdAt, String state, int retryCount, long lastAttemptEpochMs) {
        try {
            OfflineOutboxAction action = new OfflineOutboxAction(actionType, messageId, conversationId,
                    emoji, "key-" + id, createdAt, retryCount, false, 0, 0L);
            String json = action.toJson(moshi);
            return new OfflineOutboxEntity(id, "key-" + id, actionType, json, state, retryCount,
                    createdAt, lastAttemptEpochMs);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void await(Completable source) {
        TestObserver<Void> observer = source.test();
        observer.awaitDone(5, TimeUnit.SECONDS).assertNoErrors().assertComplete();
    }

    private static void awaitError(Completable source, Class<? extends Throwable> expected) {
        TestObserver<Void> observer = source.test();
        observer.awaitDone(5, TimeUnit.SECONDS);
        observer.assertNotComplete();
        observer.assertError(expected);
    }

    private static <T> T awaitSingle(Single<T> source) {
        TestObserver<T> observer = source.test();
        observer.awaitDone(5, TimeUnit.SECONDS).assertNoErrors().assertComplete();
        return observer.values().get(0);
    }

    private static void awaitSingleError(Single<?> source, Class<? extends Throwable> expected) {
        TestObserver<?> observer = source.test();
        observer.awaitDone(5, TimeUnit.SECONDS);
        observer.assertNotComplete();
        observer.assertError(expected);
    }

    private static String jwtFor(String userId) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + userId + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return "header." + payload + ".signature";
    }

    // ------------------------------------------------------------- payload schema

    @Test public void actionPayloadRoundTripsThroughVersionedJson() throws Exception {
        OfflineOutboxAction action = new OfflineOutboxAction(OfflineOutboxAction.REACTION_ADD,
                MESSAGE_ID, CONVERSATION_ID, "👍", "key-1", 123L, 2, false, 4, 0L);
        OfflineOutboxAction parsed = OfflineOutboxAction.fromJson(action.toJson(moshi), moshi);
        assertEquals(OfflineOutboxAction.REACTION_ADD, parsed.getActionType());
        assertEquals(MESSAGE_ID, parsed.getMessageId());
        assertEquals(CONVERSATION_ID, parsed.getConversationId());
        assertEquals("👍", parsed.getEmoji());
        assertEquals("key-1", parsed.getIdempotencyKey());
        assertEquals(123L, parsed.getCreatedAt());
        assertEquals(2, parsed.getAttemptCount());
        assertFalse(parsed.getStateBefore());
        assertEquals(4, parsed.getReactionCountBefore());
        assertTrue(parsed.desiredState());
        assertTrue(parsed.isReaction());
    }

    @Test public void actionPayloadRejectsUnsupportedVersion() {
        String json = "{\"version\":99,\"actionType\":\"PIN\",\"messageId\":\"m\","
                + "\"conversationId\":\"c\",\"idempotencyKey\":\"k\",\"createdAt\":1,"
                + "\"stateBefore\":false}";
        try {
            OfflineOutboxAction.fromJson(json, moshi);
            fail("expected an IOException");
        } catch (IOException expected) {
            // expected
        }
    }

    @Test public void actionPayloadRejectsReactionWithoutEmoji() {
        String json = "{\"version\":1,\"actionType\":\"REACTION_ADD\",\"messageId\":\"m\","
                + "\"conversationId\":\"c\",\"idempotencyKey\":\"k\",\"createdAt\":1,"
                + "\"stateBefore\":false}";
        try {
            OfflineOutboxAction.fromJson(json, moshi);
            fail("expected an IOException");
        } catch (IOException expected) {
            // expected
        }
    }

    // ------------------------------------------------------------------ producer

    @Test public void toggleReactionEnqueuesPendingActionInSameTransactionAsOptimisticRow() {
        await(repository.toggleReaction(MESSAGE_ID, CONVERSATION_ID, "👍"));

        MessageReactionEntity reaction = dao.reaction("👍");
        assertNotNull(reaction);
        assertEquals(1, reaction.getCount());
        assertTrue(reaction.getReactedByMe());

        OfflineOutboxEntity row = dao.onlyRow();
        assertNotNull(row);
        assertEquals(OfflineOutboxAction.REACTION_ADD, row.getActionType());
        assertEquals(OutboxState.PENDING, row.getState());
        assertEquals(0, row.getRetryCount());
        OfflineOutboxAction action = payload(row);
        assertEquals(row.getIdempotencyKey(), action.getIdempotencyKey());
        assertEquals(MESSAGE_ID, action.getMessageId());
        assertEquals(CONVERSATION_ID, action.getConversationId());
        assertEquals("👍", action.getEmoji());
        assertFalse(action.getStateBefore());
        assertEquals(0, action.getReactionCountBefore());
        assertEquals(0, action.getAttemptCount());

        assertTrue("reaction write ran outside the transaction: " + dao.operations,
                dao.operations.stream().anyMatch(op ->
                        op.startsWith("insertReactionsBlocking") && op.endsWith(":1")));
        assertTrue("outbox insert ran outside the transaction: " + dao.operations,
                dao.operations.stream().anyMatch(op ->
                        op.startsWith("insertBlocking") && op.endsWith(":1")));
        verify(scheduler).schedule();
    }

    @Test public void enqueueFailureRollsBackOptimisticReactionWrite() {
        dao.failNextInsertBlocking = true;
        awaitError(repository.toggleReaction(MESSAGE_ID, CONVERSATION_ID, "👍"),
                IllegalStateException.class);
        assertNull("optimistic reaction survived a failed transaction", dao.reaction("👍"));
        assertTrue("outbox row survived a failed transaction", dao.rows().isEmpty());
    }

    @Test public void contradictoryReactionPairCollapsesBeforeSync() {
        await(repository.toggleReaction(MESSAGE_ID, CONVERSATION_ID, "👍"));
        assertNotNull(dao.reaction("👍"));
        await(repository.toggleReaction(MESSAGE_ID, CONVERSATION_ID, "👍"));
        assertTrue("contradictory pair should leave no queued action", dao.rows().isEmpty());
        assertNull("reaction should be back to its pre-pair state", dao.reaction("👍"));
    }

    @Test public void removeThenReaddCollapsesAndRestoresServerCount() {
        dao.insertReactionsBlocking(List.of(
                new MessageReactionEntity(MESSAGE_ID, CONVERSATION_ID, "👍", 2, true)));
        await(repository.toggleReaction(MESSAGE_ID, CONVERSATION_ID, "👍"));
        await(repository.toggleReaction(MESSAGE_ID, CONVERSATION_ID, "👍"));
        assertTrue(dao.rows().isEmpty());
        MessageReactionEntity restored = dao.reaction("👍");
        assertNotNull(restored);
        assertEquals(2, restored.getCount());
        assertTrue(restored.getReactedByMe());
    }

    @Test public void differentEmojiDoesNotCollapse() {
        await(repository.toggleReaction(MESSAGE_ID, CONVERSATION_ID, "👍"));
        await(repository.toggleReaction(MESSAGE_ID, CONVERSATION_ID, "❤️"));
        assertEquals(2, dao.rows().size());
        assertEquals(2, dao.reactions().size());
    }

    @Test public void pinMessageEnqueuesPinAction() {
        await(repository.pinMessage(CONVERSATION_ID, MESSAGE_ID));
        assertNotNull(dao.findPinBlocking(CONVERSATION_ID, MESSAGE_ID));
        OfflineOutboxEntity row = dao.onlyRow();
        assertEquals(OfflineOutboxAction.PIN, row.getActionType());
        assertFalse(payload(row).getStateBefore());
        verify(scheduler).schedule();
    }

    @Test public void unpinMessageEnqueuesUnpinAction() {
        dao.insertPinBlocking(new PinnedMessageEntity(CONVERSATION_ID, MESSAGE_ID, 5L));
        await(repository.unpinMessage(CONVERSATION_ID, MESSAGE_ID));
        assertNull(dao.findPinBlocking(CONVERSATION_ID, MESSAGE_ID));
        OfflineOutboxEntity row = dao.onlyRow();
        assertEquals(OfflineOutboxAction.UNPIN, row.getActionType());
        assertTrue(payload(row).getStateBefore());
    }

    @Test public void contradictoryPinPairCollapsesBeforeSync() {
        await(repository.pinMessage(CONVERSATION_ID, MESSAGE_ID));
        await(repository.unpinMessage(CONVERSATION_ID, MESSAGE_ID));
        assertTrue(dao.rows().isEmpty());
        assertNull(dao.findPinBlocking(CONVERSATION_ID, MESSAGE_ID));
    }

    // --------------------------------------------------------------- dispatcher

    @Test public void dispatchSuccessMarksSent() {
        await(repository.toggleReaction(MESSAGE_ID, CONVERSATION_ID, "👍"));
        OfflineOutboxEntity row = dao.onlyRow();
        actionApi.results.add(new ApiResult.Success<>(null));

        await(worker().drainOfflineOutbox());

        assertEquals(OutboxState.SENT, dao.rows().get(0).getState());
        assertEquals(1, actionApi.dispatched.size());
        assertEquals(row.getIdempotencyKey(), actionApi.dispatched.get(0).getIdempotencyKey());
    }

    @Test public void dispatchNetworkErrorReleasesLeaseAndStopsDrain() {
        await(repository.toggleReaction(MESSAGE_ID, CONVERSATION_ID, "👍"));
        await(repository.toggleReaction(MESSAGE_ID, CONVERSATION_ID, "❤️"));
        actionApi.results.add(new ApiResult.NetworkError<>(new IOException("offline")));

        awaitError(worker().drainOfflineOutbox(), RuntimeException.class);

        assertEquals("only the first item should be attempted", 1, actionApi.dispatched.size());
        List<OfflineOutboxEntity> rows = dao.rows();
        assertEquals(OutboxState.PENDING, rows.get(0).getState());
        assertEquals(1, rows.get(0).getRetryCount());
        assertEquals(OutboxState.PENDING, rows.get(1).getState());
        assertEquals(0, rows.get(1).getRetryCount());
    }

    @Test public void dispatchPermanent4xxMarksFailedAndRollsBack() {
        await(repository.toggleReaction(MESSAGE_ID, CONVERSATION_ID, "👍"));
        io.reactivex.rxjava3.subscribers.TestSubscriber<ApiError> events =
                repository.getInteractionEvents().test();
        actionApi.results.add(new ApiResult.HttpError<>(403, "FORBIDDEN"));

        await(worker().drainOfflineOutbox());

        assertEquals(OutboxState.FAILED, dao.rows().get(0).getState());
        assertNull("optimistic reaction should roll back on permanent failure", dao.reaction("👍"));
        assertEquals(1, events.values().size());
        assertTrue(events.values().get(0) instanceof ApiError.Unknown);
        events.cancel();
    }

    @Test public void retryScheduleReleasesLeaseUntilMaxAttemptsThenFails() {
        await(repository.toggleReaction(MESSAGE_ID, CONVERSATION_ID, "👍"));
        actionApi.results.add(new ApiResult.NetworkError<>(new IOException("offline")));
        OutboxSyncWorker worker = worker();

        for (int attempt = 1; attempt <= OutboxSyncWorker.MAX_ATTEMPTS; attempt++) {
            if (attempt < OutboxSyncWorker.MAX_ATTEMPTS) {
                awaitError(worker.drainOfflineOutbox(), RuntimeException.class);
                OfflineOutboxEntity row = dao.rows().get(0);
                assertEquals(OutboxState.PENDING, row.getState());
                assertEquals(attempt, row.getRetryCount());
            } else {
                await(worker.drainOfflineOutbox());
                assertEquals(OutboxState.FAILED, dao.rows().get(0).getState());
            }
        }
        assertEquals(OutboxSyncWorker.MAX_ATTEMPTS, actionApi.dispatched.size());
        assertNull("failed reaction should roll back", dao.reaction("👍"));
    }

    @Test public void retryKeepsTheOriginalIdempotencyKey() {
        await(repository.toggleReaction(MESSAGE_ID, CONVERSATION_ID, "👍"));
        String key = dao.rows().get(0).getIdempotencyKey();
        actionApi.results.add(new ApiResult.NetworkError<>(new IOException("offline")));
        OutboxSyncWorker worker = worker();
        awaitError(worker.drainOfflineOutbox(), RuntimeException.class);

        actionApi.results.clear();
        actionApi.results.add(new ApiResult.Success<>(null));
        await(worker.drainOfflineOutbox());

        assertEquals(2, actionApi.dispatched.size());
        assertEquals(key, actionApi.dispatched.get(0).getIdempotencyKey());
        assertEquals(key, actionApi.dispatched.get(1).getIdempotencyKey());
        assertEquals(OutboxState.SENT, dao.rows().get(0).getState());
    }

    @Test public void staleLeaseIsReclaimedAndDispatched() {
        long stale = System.currentTimeMillis() - 120_000L;
        dao.insertBlocking(row("row-stale", OfflineOutboxAction.REACTION_ADD, MESSAGE_ID,
                CONVERSATION_ID, "👍", 1000L, OutboxState.IN_PROGRESS, 2, stale));
        actionApi.results.add(new ApiResult.Success<>(null));

        await(worker().drainOfflineOutbox());

        assertEquals(OutboxState.SENT, dao.rows().get(0).getState());
        assertEquals(1, actionApi.dispatched.size());
    }

    @Test public void dispatchFollowsFifoOrder() {
        OfflineOutboxEntity first = row("row-1", OfflineOutboxAction.REACTION_ADD, MESSAGE_ID,
                CONVERSATION_ID, "👍", 1000L, OutboxState.PENDING, 0, 0L);
        OfflineOutboxEntity second = row("row-2", OfflineOutboxAction.REACTION_ADD, MESSAGE_ID,
                CONVERSATION_ID, "❤️", 2000L, OutboxState.PENDING, 0, 0L);
        dao.insertBlocking(second);
        dao.insertBlocking(first);
        actionApi.results.add(new ApiResult.Success<>(null));

        await(worker().drainOfflineOutbox());

        assertEquals(2, actionApi.dispatched.size());
        assertEquals(first.getIdempotencyKey(), actionApi.dispatched.get(0).getIdempotencyKey());
        assertEquals(second.getIdempotencyKey(), actionApi.dispatched.get(1).getIdempotencyKey());
    }

    @Test public void emptyQueueCompletesWithoutDispatching() {
        await(worker().drainOfflineOutbox());
        assertTrue(actionApi.dispatched.isEmpty());
    }

    @Test public void newWorkerResumesAfterProcessRestart() {
        await(repository.toggleReaction(MESSAGE_ID, CONVERSATION_ID, "👍"));
        await(repository.toggleReaction(MESSAGE_ID, CONVERSATION_ID, "❤️"));
        actionApi.results.add(new ApiResult.NetworkError<>(new IOException("offline")));
        awaitError(worker().drainOfflineOutbox(), RuntimeException.class);
        assertEquals(1, actionApi.dispatched.size());

        RecordingActionApi freshApi = new RecordingActionApi();
        freshApi.results.add(new ApiResult.Success<>(null));
        MessageRepository restarted = newRepository(freshApi);
        try {
            await(new OutboxSyncWorker(mock(Context.class), mock(WorkerParameters.class),
                    restarted, dao).drainOfflineOutbox());
        } finally {
            restarted.dispose();
        }

        assertEquals(2, freshApi.dispatched.size());
        assertEquals(OutboxState.SENT, dao.rows().get(0).getState());
        assertEquals(OutboxState.SENT, dao.rows().get(1).getState());
    }

    @Test public void dispatchRejectsPayloadThatDoesNotMatchRow() {
        OfflineOutboxEntity row = row("row-1", OfflineOutboxAction.REACTION_ADD, MESSAGE_ID,
                CONVERSATION_ID, "👍", 1000L, OutboxState.PENDING, 0, 0L);
        OfflineOutboxEntity mismatched = new OfflineOutboxEntity(row.getId(), row.getIdempotencyKey(),
                OfflineOutboxAction.REACTION_REMOVE, row.getPayloadJson(), OutboxState.PENDING, 0,
                row.getCreatedAtEpochMs(), 0L);
        awaitSingleError(repository.dispatchOfflineAction(mismatched), IOException.class);
    }

    @Test public void payloadWithAttemptCountPreservesIdempotencyKey() throws Exception {
        await(repository.toggleReaction(MESSAGE_ID, CONVERSATION_ID, "👍"));
        OfflineOutboxEntity row = dao.onlyRow();
        OfflineOutboxAction parsed = OfflineOutboxAction.fromJson(
                repository.payloadWithAttemptCount(row, 3), moshi);
        assertEquals(row.getIdempotencyKey(), parsed.getIdempotencyKey());
        assertEquals(3, parsed.getAttemptCount());
        assertEquals(row.getActionType(), parsed.getActionType());
    }

    @Test public void accountBoundaryClearsPendingOfflineActions() {
        await(repository.toggleReaction(MESSAGE_ID, CONVERSATION_ID, "👍"));
        assertEquals(1, dao.rows().size());

        doAnswer(invocation -> {
            dao.clearBlocking();
            return null;
        }).when(appDatabase).clearAllTables();
        AccountDataCleaner cleaner = new AccountDataCleaner(
                appDatabase, new SessionOperationTracker(), Completable::complete);
        await(cleaner.clearForSessionBoundary());

        assertTrue("logout must drop actions queued for the previous session", dao.rows().isEmpty());
    }

    // ------------------------------------------------------------ reconciliation

    @Test public void deltaSyncAfterDrainedReactionOverwritesWithoutDoubleApply() {
        await(repository.toggleReaction(MESSAGE_ID, CONVERSATION_ID, "👍"));
        actionApi.results.add(new ApiResult.Success<>(null));
        await(worker().drainOfflineOutbox());
        assertEquals(OutboxState.SENT, dao.rows().get(0).getState());
        assertEquals(1, dao.reaction("👍").getCount());

        sessionEventStream.onNext(new ChatEvent.ReactionUpdated(CONVERSATION_ID, MESSAGE_ID, List.of(
                new ReactionSummaryDto("👍", 4, true))));

        MessageReactionEntity reconciled = dao.reaction("👍");
        assertNotNull(reconciled);
        assertEquals(4, reconciled.getCount());
        assertTrue(reconciled.getReactedByMe());
        assertEquals("delta must replace, not append", 1, dao.reactions().size());
    }

    @Test public void deltaSyncAfterDrainedPinOverwritesWithoutDoubleApply() {
        await(repository.pinMessage(CONVERSATION_ID, MESSAGE_ID));
        actionApi.results.add(new ApiResult.Success<>(null));
        await(worker().drainOfflineOutbox());
        assertNotNull(dao.findPinBlocking(CONVERSATION_ID, MESSAGE_ID));

        sessionEventStream.onNext(new ChatEvent.PinUpdated(CONVERSATION_ID, MESSAGE_ID, false));

        assertNull(dao.findPinBlocking(CONVERSATION_ID, MESSAGE_ID));
    }

    // ------------------------------------------------------- permanent rollback

    @Test public void permanentFailureOfAddRemovesOptimisticReaction() {
        await(repository.toggleReaction(MESSAGE_ID, CONVERSATION_ID, "👍"));
        OfflineOutboxEntity row = dao.onlyRow();
        await(dao.claimLease(row.getId(), System.currentTimeMillis(), 0L).ignoreElement());
        await(repository.failOfflineAction(row, new ApiError.Unknown("FORBIDDEN")));
        assertEquals(OutboxState.FAILED, dao.rows().get(0).getState());
        assertNull(dao.reaction("👍"));
    }

    @Test public void permanentFailureOfRemoveRestoresReaction() {
        dao.insertReactionsBlocking(List.of(
                new MessageReactionEntity(MESSAGE_ID, CONVERSATION_ID, "👍", 2, true)));
        await(repository.toggleReaction(MESSAGE_ID, CONVERSATION_ID, "👍"));
        MessageReactionEntity optimisticRemove = dao.reaction("👍");
        assertNotNull(optimisticRemove);
        assertEquals(1, optimisticRemove.getCount());
        assertFalse(optimisticRemove.getReactedByMe());
        OfflineOutboxEntity row = dao.onlyRow();
        await(dao.claimLease(row.getId(), System.currentTimeMillis(), 0L).ignoreElement());
        await(repository.failOfflineAction(row, new ApiError.Unknown("FORBIDDEN")));
        MessageReactionEntity restored = dao.reaction("👍");
        assertNotNull(restored);
        assertEquals(2, restored.getCount());
        assertTrue(restored.getReactedByMe());
    }

    @Test public void permanentFailureDropsRedundantLaterAction() {
        await(repository.toggleReaction(MESSAGE_ID, CONVERSATION_ID, "👍"));
        OfflineOutboxEntity add = dao.onlyRow();
        await(dao.claimLease(add.getId(), System.currentTimeMillis(), 0L).ignoreElement());
        await(repository.toggleReaction(MESSAGE_ID, CONVERSATION_ID, "👍"));
        assertEquals(2, dao.rows().size());
        await(repository.failOfflineAction(add, new ApiError.Unknown("FORBIDDEN")));
        assertEquals(1, dao.rows().size());
        assertEquals(OutboxState.FAILED, dao.rows().get(0).getState());
        assertNull(dao.reaction("👍"));
    }

    @Test public void permanentFailureOfPinRemovesOptimisticPin() {
        await(repository.pinMessage(CONVERSATION_ID, MESSAGE_ID));
        OfflineOutboxEntity row = dao.onlyRow();
        await(dao.claimLease(row.getId(), System.currentTimeMillis(), 0L).ignoreElement());
        await(repository.failOfflineAction(row, new ApiError.Unknown("FORBIDDEN")));
        assertNull(dao.findPinBlocking(CONVERSATION_ID, MESSAGE_ID));
    }

    @Test public void permanentFailureOfUnpinRestoresPin() {
        dao.insertPinBlocking(new PinnedMessageEntity(CONVERSATION_ID, MESSAGE_ID, 7L));
        await(repository.unpinMessage(CONVERSATION_ID, MESSAGE_ID));
        OfflineOutboxEntity row = dao.onlyRow();
        await(dao.claimLease(row.getId(), System.currentTimeMillis(), 0L).ignoreElement());
        await(repository.failOfflineAction(row, new ApiError.Unknown("FORBIDDEN")));
        assertNotNull(dao.findPinBlocking(CONVERSATION_ID, MESSAGE_ID));
    }

    // ------------------------------------------------------- set-endpoint fallback

    @Test public void reactionSetEndpointFallsBackToToggleWhenStateDiffers() {
        MessageApi api = mock(MessageApi.class);
        when(api.addReaction(eq(MESSAGE_ID), eq("👍"), anyString()))
                .thenReturn(fakeCall(Response.error(404, errorBody())));
        when(api.toggleReaction(eq(MESSAGE_ID), any(ReactionRequest.class), anyString()))
                .thenReturn(fakeCall(Response.success(new ReactionUpdateDto(MESSAGE_ID,
                        List.of(new ReactionSummaryDto("👍", 2, true))))));
        RetrofitOfflineActionApi actionApi = new RetrofitOfflineActionApi(api);
        OfflineOutboxAction action = new OfflineOutboxAction(OfflineOutboxAction.REACTION_ADD,
                MESSAGE_ID, CONVERSATION_ID, "👍", "key-1", 1L, 0, false, 0, 0L);

        ApiResult<?> result = awaitSingle(actionApi.dispatch(action));

        assertTrue(result instanceof ApiResult.Success);
        verify(api).toggleReaction(eq(MESSAGE_ID), any(ReactionRequest.class), eq("key-1"));
    }

    @Test public void reactionSetEndpointFallsBackToToggleOn405Too() {
        MessageApi api = mock(MessageApi.class);
        when(api.addReaction(eq(MESSAGE_ID), eq("👍"), anyString()))
                .thenReturn(fakeCall(Response.error(405, errorBody())));
        when(api.toggleReaction(eq(MESSAGE_ID), any(ReactionRequest.class), anyString()))
                .thenReturn(fakeCall(Response.success(new ReactionUpdateDto(MESSAGE_ID,
                        List.of(new ReactionSummaryDto("👍", 2, true))))));
        RetrofitOfflineActionApi actionApi = new RetrofitOfflineActionApi(api);
        OfflineOutboxAction action = new OfflineOutboxAction(OfflineOutboxAction.REACTION_ADD,
                MESSAGE_ID, CONVERSATION_ID, "👍", "key-1", 1L, 0, false, 0, 0L);

        assertTrue(awaitSingle(actionApi.dispatch(action)) instanceof ApiResult.Success);
        verify(api).toggleReaction(eq(MESSAGE_ID), any(ReactionRequest.class), eq("key-1"));
    }

    @Test public void reactionSetEndpointDoesNotToggleWhenDesiredStateAlreadyMatches() {
        MessageApi api = mock(MessageApi.class);
        when(api.addReaction(eq(MESSAGE_ID), eq("👍"), anyString()))
                .thenReturn(fakeCall(Response.error(404, errorBody())));
        RetrofitOfflineActionApi actionApi = new RetrofitOfflineActionApi(api);
        OfflineOutboxAction action = new OfflineOutboxAction(OfflineOutboxAction.REACTION_ADD,
                MESSAGE_ID, CONVERSATION_ID, "👍", "key-1", 1L, 0, true, 1, 0L);

        assertTrue("no-op should succeed without toggling",
                awaitSingle(actionApi.dispatch(action)) instanceof ApiResult.Success);
        verify(api, never()).toggleReaction(anyString(), any(ReactionRequest.class), anyString());
    }

    @Test public void reactionSetEndpointDoesNotFallbackOnOtherErrors() {
        MessageApi api = mock(MessageApi.class);
        when(api.addReaction(eq(MESSAGE_ID), eq("👍"), anyString()))
                .thenReturn(fakeCall(Response.error(403, errorBody())));
        RetrofitOfflineActionApi actionApi = new RetrofitOfflineActionApi(api);
        OfflineOutboxAction action = new OfflineOutboxAction(OfflineOutboxAction.REACTION_ADD,
                MESSAGE_ID, CONVERSATION_ID, "👍", "key-1", 1L, 0, false, 0, 0L);

        ApiResult<?> result = awaitSingle(actionApi.dispatch(action));

        assertTrue(result instanceof ApiResult.HttpError);
        assertEquals(403, ((ApiResult.HttpError<?>) result).getStatus());
        verify(api, never()).toggleReaction(anyString(), any(ReactionRequest.class), anyString());
    }

    @Test public void removeReactionSetEndpointFallsBackToToggle() {
        MessageApi api = mock(MessageApi.class);
        when(api.removeReaction(eq(MESSAGE_ID), eq("👍"), anyString()))
                .thenReturn(fakeCall(Response.error(404, errorBody())));
        when(api.toggleReaction(eq(MESSAGE_ID), any(ReactionRequest.class), anyString()))
                .thenReturn(fakeCall(Response.success(new ReactionUpdateDto(MESSAGE_ID, List.of()))));
        RetrofitOfflineActionApi actionApi = new RetrofitOfflineActionApi(api);
        OfflineOutboxAction action = new OfflineOutboxAction(OfflineOutboxAction.REACTION_REMOVE,
                MESSAGE_ID, CONVERSATION_ID, "👍", "key-1", 1L, 0, true, 1, 0L);

        assertTrue(awaitSingle(actionApi.dispatch(action)) instanceof ApiResult.Success);
        verify(api).toggleReaction(eq(MESSAGE_ID), any(ReactionRequest.class), eq("key-1"));
    }

    // ------------------------------------------------------------------ helpers

    private static ResponseBody errorBody() {
        return ResponseBody.create(null, "{}");
    }

    private static <T> Call<T> fakeCall(Response<T> response) {
        return new Call<T>() {
            @Override public Response<T> execute() {
                return response;
            }

            @Override public void enqueue(Callback<T> callback) {
            }

            @Override public boolean isExecuted() {
                return false;
            }

            @Override public void cancel() {
            }

            @Override public boolean isCanceled() {
                return false;
            }

            @Override public Call<T> clone() {
                return this;
            }

            @Override public okhttp3.Request request() {
                return new okhttp3.Request.Builder().url("http://localhost/").build();
            }

            @Override public okio.Timeout timeout() {
                return new okio.Timeout();
            }
        };
    }

    /** Records dispatches and replays a scripted sequence of results. */
    private static final class RecordingActionApi implements OfflineActionApi {
        private final List<OfflineOutboxAction> dispatched = new ArrayList<>();
        private final List<ApiResult<?>> results = new ArrayList<>();

        @Override public Single<ApiResult<?>> dispatch(OfflineOutboxAction action) {
            return Single.fromCallable(() -> {
                dispatched.add(action);
                if (results.isEmpty()) return new ApiResult.Success<>(null);
                if (results.size() == 1) return results.get(0);
                return results.remove(0);
            });
        }
    }
}
