package com.nexachat.data;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.nexachat.core.database.*;
import com.nexachat.core.network.*;
import com.nexachat.core.network.dto.*;
import com.nexachat.core.storage.*;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import io.reactivex.rxjava3.observers.TestObserver;
import org.junit.Before;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;

/** Contract fixtures, deliberately independent of the unmerged V32 implementation. */
public final class SyncEngineV2Test {
    private final Moshi moshi = new Moshi.Builder().build();
    private final Map<String, MessageEntity> rows = new LinkedHashMap<>();
    private final List<ConversationWatermark> conversations = new ArrayList<>();
    private final List<SyncRequest> requests = new ArrayList<>();
    private final Map<String, SyncCursorEntity> cursors = new LinkedHashMap<>();
    private final Map<String, List<MessageReactionEntity>> reactionRows = new LinkedHashMap<>();
    private final Set<String> queued = new HashSet<>();
    private final Map<String, Integer> unreadCounts = new LinkedHashMap<>();
    private AppDatabase db;
    private MessageDao messages;
    private ConversationDao inbox;
    private ReactionDao reactions;
    private MessageApi api;
    private TokenStore tokens;
    private TokenRefresher refresher;
    private SyncEngine engine;
    private SessionOperationTracker tracker;
    private java.util.function.Function<SyncRequest, String> server;

    @Before public void setUp() throws Exception {
        db = mock(AppDatabase.class);
        messages = mock(MessageDao.class);
        inbox = mock(ConversationDao.class);
        reactions = mock(ReactionDao.class);
        api = mock(MessageApi.class);
        tokens = mock(TokenStore.class);
        when(tokens.readTokens()).thenReturn(Maybe.just(new Tokens(jwt("A"), Long.MAX_VALUE, "fixture")));
        when(tokens.readSyncServerTime(anyString())).thenReturn(Maybe.empty());
        when(tokens.saveSyncServerTime(anyString(), anyString())).thenReturn(Completable.complete());
        refresher = new TokenRefresher(tokens, new SessionEvents(), () -> null);
        refresher.onSessionEstablished(1L);
        when(db.messageDao()).thenReturn(messages);
        when(db.conversationDao()).thenReturn(inbox);
        when(db.reactionDao()).thenReturn(reactions);
        SyncCursorDao cursorDao = mock(SyncCursorDao.class);
        when(db.syncCursorDao()).thenReturn(cursorDao);
        when(cursorDao.find(anyString(), anyString())).thenAnswer(i ->
                cursors.get(i.getArgument(0) + ":" + i.getArgument(1)));
        doAnswer(i -> { SyncCursorEntity cursor = i.getArgument(0);
            cursors.put(cursor.ownerUserId + ":" + cursor.conversationId, cursor); return null;
        }).when(cursorDao).put(any());
        doAnswer(i -> { cursors.remove(i.getArgument(0) + ":" + i.getArgument(1)); return null;
        }).when(cursorDao).delete(anyString(), anyString());
        PinDao pins = mock(PinDao.class);
        OutboxDao outbox = mock(OutboxDao.class);
        when(db.pinDao()).thenReturn(pins);
        when(db.outboxDao()).thenReturn(outbox);
        when(pins.deleteForConversation(anyString())).thenReturn(Completable.complete());
        when(pins.delete(anyString(), anyString())).thenReturn(Completable.complete());
        when(outbox.deleteForConversation(anyString(), anyString())).thenReturn(Completable.complete());
        when(outbox.delete(anyString(), anyString())).thenAnswer(i -> Completable.fromAction(() ->
                queued.remove(i.getArgument(1) + ":" + i.getArgument(0))));
        when(inbox.getAllWatermarks()).thenAnswer(i -> Single.just(new ArrayList<>(conversations)));
        when(messages.getAllMaxSeqs()).thenReturn(Single.just(List.of()));
        when(messages.byId(anyString())).thenAnswer(i -> Maybe.fromCallable(() -> rows.get(i.getArgument(0))));
        when(messages.upsert(any())).thenAnswer(i -> Completable.fromAction(() -> {
            MessageEntity row = i.getArgument(0); rows.putIfAbsent(row.getId(), row);
        }));
        when(messages.upsertAll(anyList())).thenAnswer(i -> Completable.fromAction(() -> {
            for (MessageEntity row : (List<MessageEntity>) i.getArgument(0)) rows.putIfAbsent(row.getId(), row);
        }));
        when(messages.update(any())).thenAnswer(i -> Completable.fromAction(() -> {
            MessageEntity row = i.getArgument(0); rows.put(row.getId(), row);
        }));
        when(messages.clearReplySnippets(anyString())).thenReturn(Completable.complete());
        when(inbox.byId(anyString())).thenAnswer(i -> Maybe.fromCallable(() ->
                conversations.stream().anyMatch(c -> c.getConversationId().equals(i.getArgument(0)))
                        ? conversation(i.getArgument(0)) : null));
        when(inbox.updateLastMessage(anyString(), anyLong(), anyString(), anyString(), anyLong(), anyBoolean(),
                any()))
                .thenReturn(Completable.complete());
        when(inbox.updateLastMessageContent(anyString(), anyLong(), anyString()))
                .thenReturn(Completable.complete());
        when(inbox.deleteById(anyString())).thenReturn(Completable.complete());
        when(inbox.markLastMessageDeleted(anyString())).thenReturn(Completable.complete());
        when(inbox.incrementUnread(anyString(), anyInt())).thenAnswer(i -> {
            unreadCounts.merge(i.getArgument(0), i.getArgument(1), Integer::sum);
            return Completable.complete();
        });
        when(messages.deleteForConversation(anyString())).thenAnswer(i -> Completable.fromAction(() ->
                rows.values().removeIf(row -> row.getConversationId().equals(i.getArgument(0)))));
        when(reactions.deleteForConversation(anyString())).thenReturn(Completable.complete());
        doAnswer(i -> { reactionRows.put(i.getArgument(0), new ArrayList<>(i.getArgument(1))); return null;
        }).when(reactions).replaceForMessageInTransaction(anyString(), anyList());
        doAnswer(i -> { reactionRows.remove(i.getArgument(0)); return null;
        }).when(reactions).deleteForMessageInTransaction(anyString());
        // Transaction simulation with rollback. Actual Room/SQLite proof is the instrumented test.
        doAnswer(i -> {
            Map<String, MessageEntity> beforeRows = new LinkedHashMap<>(rows);
            Map<String, SyncCursorEntity> beforeCursors = new LinkedHashMap<>(cursors);
            Map<String, List<MessageReactionEntity>> beforeReactions = new LinkedHashMap<>(reactionRows);
            Map<String, Integer> beforeUnread = new LinkedHashMap<>(unreadCounts);
            Set<String> beforeQueued = new HashSet<>(queued);
            try { ((Runnable) i.getArgument(0)).run(); }
            catch (Throwable error) { rows.clear(); rows.putAll(beforeRows);
                cursors.clear(); cursors.putAll(beforeCursors);
                reactionRows.clear(); reactionRows.putAll(beforeReactions);
                unreadCounts.clear(); unreadCounts.putAll(beforeUnread);
                queued.clear(); queued.addAll(beforeQueued); throw error; }
            return null;
        })
                .when(db).runInTransaction(any(Runnable.class));
        when(api.syncDeltas(any())).thenAnswer(i -> {
            SyncRequest request = i.getArgument(0);
            requests.add(request);
            return response(moshi.adapter(SyncResponse.class).fromJson(server.apply(request)));
        });
        restart();
    }

    @Test public void b2EditBetween101ConversationBatchesIsReachedOnNextCycle() {
        for (int n = 0; n < 101; n++) conversations.add(new ConversationWatermark("c" + n, 900L));
        Map<String, Long> versions = new HashMap<>();
        conversations.forEach(c -> versions.put(c.getConversationId(), 1L));
        AtomicInteger batches = new AtomicInteger();
        String[] editedConversation = {null};
        server = request -> {
            if (editedConversation[0] != null && request.getWatermarks().stream()
                    .noneMatch(w -> w.getConversationId().equals(editedConversation[0]))) {
                versions.put(editedConversation[0], 2L); // batch 1 is fully drained; batch 2 starts
            }
            List<String> deltas = new ArrayList<>();
            for (SyncRequest.Watermark wm : request.getWatermarks()) {
                long version = versions.get(wm.getConversationId());
                deltas.add(delta(wm.getConversationId(), version, false,
                        cursor(wm) < version ? message(wm.getConversationId(), version) : ""));
            }
            if (batches.incrementAndGet() == 1) {
                editedConversation[0] = request.getWatermarks().get(0).getConversationId();
            }
            return envelope(deltas, batches.get());
        };

        engine.sync().onErrorComplete().blockingAwait();
        restart();
        engine.sync().onErrorComplete().blockingAwait();

        assertEquals("all 101 conversations must be reconciled", 101, rows.size());
        assertEquals("inter-batch edit must not be hidden by another conversation's serverTime",
                "v2", rows.get("m-" + editedConversation[0]).getContent());
    }

    @Test public void pageLimitInterruptionThenProcessRestartResumesCommittedCursor() {
        conversations.add(new ConversationWatermark("c0", 900L));
        server = request -> {
            long cursor = cursor(request.getWatermarks().get(0));
            long next = Math.min(65L, cursor + 1L);
            String row = message("c0", next).replace("m-c0", "m-c0-" + next)
                    .replace("\"seq\":1,", "\"seq\":" + next + ",");
            return envelope(List.of(delta("c0", next, next < 65L, row)), (int) next);
        };

        engine.sync().onErrorComplete().blockingAwait();
        assertTrue("the page limit is pending work, never exhaustion", engine.hasPendingContinuation());
        int beforeRestart = requests.size();
        restart();
        engine.sync().onErrorComplete().blockingAwait();

        assertEquals("restart must resume the durable applied revision", 60L,
                cursor(requests.get(beforeRestart).getWatermarks().get(0)));
        assertEquals("every page must remain reachable after restart", 65, rows.size());
        assertEquals("v65", rows.get("m-c0-65").getContent());
        assertFalse(engine.hasPendingContinuation());
    }

    @Test public void crashBeforeCommitRollsBackRowsAndCursorThenReplays() {
        conversations.add(new ConversationWatermark("c0", 900));
        server = request -> envelope(List.of(delta("c0", 1, false,
                cursor(request.getWatermarks().get(0)) == 0 ? message("c0", 1) : "")), 1);
        engine.setSyncFaultPoints(() -> { throw new SimulatedCrash(); }, () -> {});
        engine.sync().test().awaitDone(5, TimeUnit.SECONDS).assertError(SimulatedCrash.class);
        assertTrue(rows.isEmpty());
        assertTrue(cursors.isEmpty());
        restart();
        engine.sync().blockingAwait();
        assertEquals(0, cursor(requests.get(1).getWatermarks().get(0)));
        assertEquals("v1", rows.get("m-c0").getContent());
        assertEquals(1, cursors.get("A:c0").changeCursor);
    }

    @Test public void crashAfterCommitResumesWithoutReapplyingTheRow() {
        conversations.add(new ConversationWatermark("c0", 900));
        server = request -> envelope(List.of(delta("c0", 1, false,
                cursor(request.getWatermarks().get(0)) == 0 ? message("c0", 1) : "")), 1);
        engine.setSyncFaultPoints(() -> {}, () -> { throw new SimulatedCrash(); });
        engine.sync().test().awaitDone(5, TimeUnit.SECONDS).assertError(SimulatedCrash.class);
        assertEquals(1, rows.size());
        assertEquals(1, cursors.get("A:c0").changeCursor);
        restart();
        engine.sync().blockingAwait();
        assertEquals(1, cursor(requests.get(1).getWatermarks().get(0)));
        verify(messages, times(1)).upsert(any());
        verify(messages, never()).update(any());
    }

    @Test public void overlappingFcmAndReconnectJoinExactlyOneFlight() throws Exception {
        seed(1, 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch joined = new CountDownLatch(1);
        engine.onFlightJoined = joined::countDown;
        AtomicInteger calls = new AtomicInteger();
        server = request -> {
            calls.incrementAndGet(); entered.countDown(); await(release);
            return envelope(List.of(delta("c0", 1, false, "")), 1);
        };
        TestObserver<Void> first = engine.sync().test();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            TestObserver<Void> second = engine.sync().test();
            assertTrue("second subscriber must join while HTTP is held", joined.await(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            first.dispose(); // the other subscriber still owns the shared drain
            release.countDown();
            second.awaitDone(5, TimeUnit.SECONDS).assertComplete().assertNoErrors();
            assertEquals(1, calls.get());
        } finally { release.countDown(); }
        engine.sync().blockingAwait();
        assertEquals("terminal flight is released", 2, calls.get());
    }

    @Test public void oldAResponseCannotWriteIntoBOrIntoANewAEpoch() throws Exception {
        seed(1, 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server = request -> { entered.countDown(); await(release);
            return envelope(List.of(delta("c0", 2, false, message("c0", 2))), 2); };
        TestObserver<Void> old = engine.sync().test();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            when(tokens.readTokens()).thenReturn(Maybe.just(new Tokens(jwt("B"), Long.MAX_VALUE, "fixture")));
            refresher.onSessionEstablished(2L);
            when(tokens.readTokens()).thenReturn(Maybe.just(new Tokens(jwt("A"), Long.MAX_VALUE, "fixture")));
            refresher.onSessionEstablished(3L);
        } finally { release.countDown(); }
        old.awaitDone(5, TimeUnit.SECONDS).assertComplete().assertNoErrors();
        assertEquals("v1", rows.get("m-c0").getContent());
        assertEquals(1, cursors.get("A:c0").changeCursor);
        assertNull(cursors.get("B:c0"));
        engine.sync().blockingAwait();
        assertEquals("v2", rows.get("m-c0").getContent());
    }

    @Test public void accountIdentityIsCheckedAgainAtTheWriteBoundary() {
        seed(1, 1);
        server = request -> {
            when(tokens.readTokens()).thenReturn(Maybe.just(new Tokens(jwt("B"), Long.MAX_VALUE, "fixture")));
            return envelope(List.of(delta("c0", 2, false, message("c0", 2))), 2);
        };
        engine.sync().blockingAwait();
        assertEquals("v1", rows.get("m-c0").getContent());
        assertEquals(1, cursors.get("A:c0").changeCursor);
    }

    @Test public void delayedPageCannotOverwriteNewerEditEventOrItsInboxPreview() {
        seed(1, 1);
        server = request -> {
            engine.applyServerMessage(parseMessage(message("c0", 3)), "c0").blockingAwait();
            return envelope(List.of(delta("c0", 2, false, message("c0", 2))), 2);
        };
        engine.sync().blockingAwait();
        assertEquals("v3", rows.get("m-c0").getContent());
        assertEquals(2, cursors.get("A:c0").changeCursor);
        verify(inbox).updateLastMessageContent("c0", 1, "v3");
        verify(inbox, never()).updateLastMessageContent("c0", 1, "v2");
        engine.applyServerMessage(parseMessage(message("c0", 2)), "c0").blockingAwait();
        assertEquals("late event also loses to the newer revision", "v3", rows.get("m-c0").getContent());
    }

    @Test public void newerDeleteCannotBeResurrectedAndCleansRepliesPinsAndReactions() {
        seed(1, 1);
        server = request -> {
            engine.applyServerDelete("m-c0", "c0", 4, 1, 4L).blockingAwait();
            return envelope(List.of(delta("c0", 2, false, message("c0", 2))), 2);
        };
        engine.sync().blockingAwait();
        assertEquals(Long.valueOf(4), rows.get("m-c0").getDeletedAtEpochMs());
        assertEquals(4, rows.get("m-c0").getChangeVersion());
        assertEquals("", rows.get("m-c0").getContent());
        assertNull(rows.get("m-c0").getAttachmentsJson());
        assertEquals("[]", rows.get("m-c0").getMentionsJson());
        verify(messages).clearReplySnippets("m-c0");
        verify(db.pinDao()).delete("c0", "m-c0");
        verify(reactions).deleteForMessageInTransaction("m-c0");
        verify(inbox).markLastMessageDeleted("c0");
        verify(inbox, never()).updateLastMessageContent("c0", 1, "v2");
    }

    @Test public void lateReplyPageCannotRestoreASnippetClearedByAParentRecall() {
        seed(1, 1);
        server = request -> {
            engine.applyServerDelete("m-c0", "c0", 4, 1, 4L).blockingAwait();
            String reply = message("c0", 2).replace("m-c0", "reply").replace("\"seq\":1", "\"seq\":2")
                    .replace("\"reactions\":[]", "\"reactions\":[],\"replyTo\":{\"id\":\"m-c0\",\"snippet\":\"private quote\"}");
            return envelope(List.of(delta("c0", 2, false, reply)), 2);
        };
        engine.sync().blockingAwait();
        assertEquals("m-c0", rows.get("reply").getReplyToId());
        assertNull(rows.get("reply").getReplyToSnippet());
    }

    @Test public void firstLifecycleEventBeforeAnyV2CursorIsRetainedAndOlderBaselineCannotRewindIt() {
        conversations.add(new ConversationWatermark("c0", 900));
        engine.applyServerMessage(parseMessage(message("c0", 3)), "c0").blockingAwait();
        assertEquals("v3", rows.get("m-c0").getContent());
        assertTrue("events never advance the applied feed cursor", cursors.isEmpty());
        server = request -> envelope(List.of(delta("c0", 2, false,
                cursor(request.getWatermarks().get(0)) == 0 ? message("c0", 2) : "")), 2);
        engine.sync().blockingAwait();
        assertEquals("v3", rows.get("m-c0").getContent());
    }

    @Test public void fullyWiredLegacyLifecycleStillWorksAndCannotDowngradeVersionedRows() {
        conversations.add(new ConversationWatermark("c0", 900));
        MessageDto legacy = parseMessage(message("c0", 1).replace("\"changeVersion\":1,", "").replace("\"generation\":1,", ""));
        engine.applyServerHistoryOrEvent(legacy, "c0", true, true).blockingAwait();
        engine.applyServerHistoryOrEvent(legacy, "c0", true, true).blockingAwait();
        assertEquals("v1", rows.get("m-c0").getContent());
        verify(inbox, times(1)).incrementUnread("c0", 1);
        rows.put("m-c0", rows.get("m-c0").withSyncVersion(3, 1).withContentAndEditedAt("newer", 3L));
        engine.applyServerMessage(legacy.withContent("old", "2026-11-12T00:00:04Z"), "c0").blockingAwait();
        engine.applyServerDelete("m-c0", "c0", 0, 0, 4L).blockingAwait();
        assertEquals("newer", rows.get("m-c0").getContent());
        assertNull(rows.get("m-c0").getDeletedAtEpochMs());
    }

    @Test public void resetDuringHttpRejectsTheDelayedPageAtTheWriteBoundary() {
        seed(1, 1);
        server = request -> {
            SyncPageStore resetter = new SyncPageStore(db, refresher, tokens, moshi);
            SyncResponse reset = parseResponse(envelope(List.of(page("c0", 0, 0, false, false, 2, "")
                    .replace("\"reset\":false", "\"reset\":true")), 1));
            resetter.apply("A", refresher.sessionEpoch(), Map.of("c0", cursors.get("A:c0")), reset);
            engine.applyServerMessage(parseMessage(message("c0", 3).replace("\"generation\":1", "\"generation\":2")), "c0").blockingAwait();
            return envelope(List.of(delta("c0", 2, false, message("c0", 2))), 2);
        };
        engine.sync().blockingAwait();
        assertEquals("v3", rows.get("m-c0").getContent());
        assertEquals(2, rows.get("m-c0").getGeneration());
        assertEquals(2, cursors.get("A:c0").generation);
        assertEquals(0, cursors.get("A:c0").changeCursor);
    }

    @Test public void explicitResetReconcilesNewGenerationWithoutDeletingQueuedSends() {
        seed(1, 1);
        AtomicInteger pages = new AtomicInteger();
        server = request -> {
            if (pages.incrementAndGet() == 1) return envelope(List.of(delta("c0", 0, false, "")
                    .replace("\"reset\":false", "\"reset\":true").replace("\"generation\":1", "\"generation\":2")), 1);
            return envelope(List.of(delta("c0", 2, false, cursor(request.getWatermarks().get(0)) == 0
                    ? message("c0", 2).replace("\"generation\":1", "\"generation\":2") : "")
                    .replace("\"generation\":1", "\"generation\":2")), 2);
        };
        engine.sync().blockingAwait();
        assertEquals(0, cursor(requests.get(1).getWatermarks().get(0)));
        assertEquals(Long.valueOf(2), requests.get(1).getWatermarks().get(0).getGeneration());
        assertEquals(2, rows.get("m-c0").getGeneration());
        assertEquals(2, cursors.get("A:c0").generation);
        verify(db.outboxDao(), never()).deleteForConversation(anyString(), anyString());
        assertEquals(1, cursors.size());
    }

    @Test public void delayedOldGenerationPageIsDiscardedWithoutDowngradingOrClearingTheCursor() {
        seed(2, 2);
        server = request -> envelope(List.of(delta("c0", 3, false, message("c0", 3))), 3);
        engine.sync().blockingAwait();
        assertEquals(2, rows.get("m-c0").getGeneration());
        assertEquals(2, cursors.get("A:c0").generation);
        assertEquals(2, cursors.get("A:c0").changeCursor);
        verify(messages, never()).clearSyncVersionsForConversation(anyString());
        assertEquals(1, requests.size());
    }

    @Test public void membershipOmissionDeletesOnlyItsAccountCursorAndCachedScope() {
        seed(1, 1);
        cursors.put("B:other", new SyncCursorEntity("B", "other", 1, 9, null, null, null, true));
        server = request -> envelope(List.of(), 1);
        engine.sync().blockingAwait();
        assertNull(cursors.get("A:c0"));
        assertNotNull(cursors.get("B:other"));
        assertTrue(rows.isEmpty());
        verify(db.outboxDao()).deleteForConversation("c0", "A");
    }

    @Test public void cacheLossStartsFromZeroEvenWhenInboxSeqIsHigh() {
        seed(9, 1);
        // Models the production DELETE trigger; its actual SQLite effect is covered by migration proof.
        rows.clear(); cursors.remove("A:c0");
        server = request -> envelope(List.of(delta("c0", 9, false,
                cursor(request.getWatermarks().get(0)) == 0 ? message("c0", 9) : "")), 9);
        restart(); engine.sync().blockingAwait();
        assertEquals(0, cursor(requests.get(0).getWatermarks().get(0)));
        assertEquals("v9", rows.get("m-c0").getContent());
    }

    @Test public void reactionOnlyContinuationKeepsItsOwnCursorAndFirstCheckpoint() {
        seed(1, 1);
        AtomicInteger pages = new AtomicInteger();
        server = request -> {
            int page = pages.incrementAndGet();
            return envelope(List.of(delta("c0", 1, false, "")
                    .replace("\"hasMore\":false", "\"hasMore\":" + (page == 1))
                    .replace("\"reset\":false", "\"reset\":false,\"reactionCursor\":" + (page == 1 ? "7" : "null")
                            + ",\"reactionUpdates\":[{\"messageId\":\"m-c0\",\"reactions\":[]}]")), page * 10);
        };
        engine.sync().blockingAwait();
        assertEquals(2, requests.size());
        assertEquals(Long.valueOf(7), requests.get(1).getWatermarks().get(0).getReactionCursor());
        assertEquals(requests.get(0).getSince(), requests.get(1).getSince());
        assertEquals("2026-11-12T00:00:10Z", cursors.get("A:c0").reactionSince);
        assertTrue(reactionRows.get("m-c0").isEmpty());
    }

    @Test public void reactionPageLimitPersistsContinuationAcrossRestart() {
        seed(1, 1);
        server = request -> {
            Long previous = request.getWatermarks().get(0).getReactionCursor();
            long next = previous == null ? 1 : previous + 1;
            return envelope(List.of(delta("c0", 1, false, "")
                    .replace("\"hasMore\":false", "\"hasMore\":" + (next < 65))
                    .replace("\"reset\":false", "\"reset\":false,\"reactionCursor\":" + (next < 65 ? next : "null"))), (int) next);
        };
        engine.sync().blockingAwait();
        assertTrue(engine.hasPendingContinuation());
        assertEquals(Long.valueOf(60), cursors.get("A:c0").reactionCursor);
        String since = requests.get(0).getSince();
        restart(); engine.sync().blockingAwait();
        assertEquals(Long.valueOf(60), requests.get(60).getWatermarks().get(0).getReactionCursor());
        assertEquals(since, requests.get(60).getSince());
        assertNull(cursors.get("A:c0").reactionCursor);
        assertEquals("2026-11-12T00:00:01Z", cursors.get("A:c0").reactionSince);
        assertFalse(engine.hasPendingContinuation());
    }

    @Test public void expiredReactionCoverageForcesFullRowReconciliationIncludingEmptyAggregates() {
        seed(1, 1);
        reactionRows.put("m-c0", List.of(new MessageReactionEntity("m-c0", "c0", "x", 1, true)));
        AtomicInteger pages = new AtomicInteger();
        server = request -> {
            int page = pages.incrementAndGet();
            if (page == 1) return envelope(List.of(delta("c0", 1, false, "")
                    .replace("\"reset\":false", "\"reset\":false,\"reactionsFullRefresh\":true")), 1);
            return envelope(List.of(delta("c0", 1, false,
                    cursor(request.getWatermarks().get(0)) == 0 ? message("c0", 1) : "")), page);
        };
        engine.sync().blockingAwait();
        assertEquals(0, cursor(requests.get(1).getWatermarks().get(0)));
        assertTrue(reactionRows.get("m-c0").isEmpty());
        assertEquals("2026-11-12T00:00:01Z", requests.get(2).getSince());
        assertFalse(cursors.get("A:c0").rebuildingReactions);
    }

    @Test public void missingPartialAndUnknownV2AllFallBackBeforeAnyV2RowIsApplied() {
        String valid = envelope(List.of(delta("c0", 1, false, message("c0", 1))), 1);
        List<String> malformed = new ArrayList<>();
        malformed.add(valid.replace("\"syncVersion\":2,", ""));
        malformed.add(valid.replace("\"syncVersion\":2", "\"syncVersion\":99"));
        malformed.add(valid.replace("\"syncVersion\":2", "\"syncVersion\":1"));
        malformed.add(valid.replace("\"changeCursor\":1", "\"changeCursor\":-1"));
        malformed.add(valid.replace("\"generation\":1", "\"generation\":0"));
        malformed.add(valid.replace("\"reset\":false", "\"reset\":true"));
        for (String field : List.of("changeCursor", "changeUpperBound", "generation", "changeHasMore", "reset", "hasMore")) {
            malformed.add(omitDeltaField(valid, field));
        }
        malformed.add("{\"syncVersion\":2,\"serverTime\":\"2026-11-12T00:00:00Z\"}");
        for (String bad : malformed) {
            conversations.clear(); conversations.add(new ConversationWatermark("c0", 900));
            rows.clear(); cursors.clear(); requests.clear(); restart();
            server = request -> request.getSyncVersion() != null ? bad
                    : "{\"deltas\":[{\"conversationId\":\"c0\",\"newMessages\":["
                            + message("c0", 1).replace("m-c0", "legacy").replace("v1", "legacy")
                            + "],\"hasMore\":false}],\"serverTime\":\"2026-11-12T00:00:01Z\"}";
            engine.sync().blockingAwait();
            assertEquals("only the explicit legacy request is applied", Set.of("legacy"), rows.keySet());
            assertTrue(cursors.isEmpty());
            assertEquals(2, requests.size());
            assertEquals(Integer.valueOf(2), requests.get(0).getSyncVersion());
            assertNull(requests.get(1).getSyncVersion());
            engine.applyServerMessage(parseMessage(message("c0", 9)), "c0").blockingAwait();
            assertEquals("late v2 events cannot escape the explicit downgrade", Set.of("legacy"), rows.keySet());
        }
    }

    @Test public void stuckPageIsBoundedAndDoesNotCommitAFabricatedCheckpoint() {
        seed(1, 1);
        server = request -> envelope(List.of(delta("c0", 1, false, "")
                .replace("\"hasMore\":false", "\"hasMore\":true")), 1);
        engine.sync().test().awaitDone(5, TimeUnit.SECONDS).assertError(IllegalStateException.class);
        assertEquals(1, requests.size());
        assertEquals("2026-11-12T00:00:00Z", cursors.get("A:c0").reactionSince);
    }

    @Test public void b1FixtureMatrixReachesEveryEditAndRecallIncludingEqualTimestamps() {
        for (boolean recall : List.of(false, true)) for (int count : new int[] {0, 1, 49, 50, 51, 60, 101}) {
            conversations.clear(); rows.clear(); cursors.clear(); requests.clear(); restart();
            conversations.add(new ConversationWatermark("c0", 900));
            cursors.put("A:c0", new SyncCursorEntity("A", "c0", 1, count,
                    "2026-11-12T00:00:00Z", null, null, false));
            List<String> changed = new ArrayList<>();
            for (int n = 1; n <= count; n++) {
                rows.put("m-" + n, new MessageEntity("m-" + n, "c0", n, "peer", "peer", "TEXT", "old",
                        null, null, null, null, 0, null, null, null).withSyncVersion(n, 1));
                String row = message("c0", count + n).replace("m-c0", "m-" + n)
                        .replace("\"seq\":1,", "\"seq\":" + n + ",")
                        .replace("\"createdAt\":", (recall ? "\"deletedAt\":\"2026-11-12T00:00:01Z\","
                                : "\"editedAt\":\"2026-11-12T00:00:01Z\",") + "\"createdAt\":");
                changed.add(row);
            }
            server = request -> {
                long after = cursor(request.getWatermarks().get(0));
                int offset = (int) Math.max(0, after - count);
                int end = Math.min(count, offset + 50);
                boolean more = end < count;
                return envelope(List.of(page("c0", more ? count + end : count * 2L, count * 2L,
                        more, more, 1, String.join(",", changed.subList(offset, end)))), 1);
            };
            engine.sync().blockingAwait();
            restart();
            engine.sync().blockingAwait();
            assertEquals(count, rows.size());
            assertEquals(count * 2L, cursors.get("A:c0").changeCursor);
            for (int n = 1; n <= count; n++) {
                MessageEntity row = rows.get("m-" + n);
                assertEquals(count + n, row.getChangeVersion());
                assertEquals(recall ? "" : "v" + (count + n), row.getContent());
                assertEquals(recall, row.getDeletedAtEpochMs() != null);
            }
        }
    }

    @Test public void mixedMessageAndReactionStreamsContinueUntilBothDrain() {
        seed(0, 1);
        AtomicInteger pages = new AtomicInteger();
        server = request -> {
            int n = pages.incrementAndGet();
            String changes = n < 3 ? message("c0", n) : "";
            return envelope(List.of(page("c0", Math.min(2, n), 2, n == 1, n < 3, 1, changes)
                    .replace("\"reset\":false", "\"reset\":false,\"reactionCursor\":" + (n < 3 ? n * 7 : "null"))), n);
        };
        engine.sync().blockingAwait();
        assertEquals(3, requests.size());
        assertEquals(Long.valueOf(2), requests.get(2).getWatermarks().get(0).getChangeCursor());
        assertEquals(Long.valueOf(14), requests.get(2).getWatermarks().get(0).getReactionCursor());
        assertEquals("v2", rows.get("m-c0").getContent());
        assertFalse(engine.hasPendingContinuation());
    }

    @Test public void reactionRemovalBetween101BatchesUsesTheFirstScopesCheckpoint() {
        for (int n = 0; n < 101; n++) {
            String c = "c" + n;
            conversations.add(new ConversationWatermark(c, 900));
            rows.put("m-" + c, new MessageEntity("m-" + c, c, 1, "peer", "peer", "TEXT", "v1",
                    null, null, null, null, 0, null, null, null).withSyncVersion(1, 1));
            cursors.put("A:" + c, new SyncCursorEntity("A", c, 1, 1,
                    "2026-11-12T00:00:00Z", null, null, false));
            reactionRows.put("m-" + c, List.of(new MessageReactionEntity("m-" + c, c, "x", 1, false)));
        }
        String[] changed = {null};
        AtomicInteger batch = new AtomicInteger();
        server = request -> {
            int n = batch.incrementAndGet();
            if (n == 1) changed[0] = request.getWatermarks().get(0).getConversationId();
            List<String> deltas = new ArrayList<>();
            for (SyncRequest.Watermark wm : request.getWatermarks()) {
                String delta = delta(wm.getConversationId(), 1, false, "");
                // The reaction is removed at t=15 after batch1(t=10), before batch2(t=20).
                if (n > 2 && wm.getConversationId().equals(changed[0])
                        && java.time.Instant.parse(request.getSince()).isBefore(java.time.Instant.parse("2026-11-12T00:00:15Z"))) {
                    delta = delta.replace("\"reset\":false", "\"reset\":false,\"reactionUpdates\":[{\"messageId\":\"m-"
                            + changed[0] + "\",\"reactions\":[]}]");
                }
                deltas.add(delta);
            }
            return envelope(deltas, n * 10);
        };
        engine.sync().blockingAwait(); restart(); engine.sync().blockingAwait();
        assertTrue(reactionRows.get("m-" + changed[0]).isEmpty());
        assertEquals("2026-11-12T00:00:10Z", requests.get(2).getSince());
    }

    @Test public void largeScalarCursorDoesNotLoseIntegerPrecision() throws Exception {
        long value = 9_007_199_254_740_993L;
        SyncResponse parsed = moshi.adapter(SyncResponse.class).fromJson(envelope(
                List.of(page("c0", value, value, false, false, 1, "")), 1));
        assertEquals(Long.valueOf(value), parsed.getDeltas().get(0).getChangeCursor());
        SyncRequest.Watermark watermark = new SyncRequest.Watermark("c0", 1, null, value, 1L);
        assertTrue(moshi.adapter(SyncRequest.Watermark.class).toJson(watermark).contains(Long.toString(value)));
    }

    @Test public void syncedPageCountsANewIncomingMessageAndDropsTheMatchingOutboxRow() {
        conversations.add(new ConversationWatermark("c0", 900));
        cursors.put("A:c0", new SyncCursorEntity("A", "c0", 1, 0,
                "2026-11-12T00:00:00Z", null, null, false));
        queued.add("A:mine-client");
        queued.add("B:mine-client");
        String incoming = message("c0", 1).replace("\"id\":\"m-c0\"", "\"id\":\"m-in\"");
        String own = message("c0", 2).replace("\"id\":\"m-c0\"", "\"id\":\"m-own\"")
                .replace("\"senderId\":\"peer\"", "\"senderId\":\"A\",\"clientMessageId\":\"mine-client\"");
        String deleted = message("c0", 3).replace("\"id\":\"m-c0\"", "\"id\":\"m-gone\"")
                .replace("\"content\":\"v3\"", "\"content\":\"x\",\"deletedAt\":\"2026-11-12T00:00:03Z\"");
        String edit = message("c0", 4).replace("\"id\":\"m-c0\"", "\"id\":\"m-in\"")
                .replace("\"content\":\"v4\"", "\"content\":\"edited\"");
        server = request -> {
            long at = cursor(request.getWatermarks().get(0));
            if (at < 3L) {
                return envelope(List.of(page("c0", 3, 3, false, false, 1,
                        incoming + "," + own + "," + deleted)), 1);
            }
            return envelope(List.of(page("c0", 4, 4, false, false, 1, edit)), 2);
        };

        engine.sync().blockingAwait();

        assertEquals(Integer.valueOf(1), unreadCounts.get("c0"));
        assertFalse(queued.contains("A:mine-client"));
        assertTrue(queued.contains("B:mine-client"));
        engine.sync().blockingAwait();
        assertEquals(Integer.valueOf(1), unreadCounts.get("c0"));
        assertEquals("edited", rows.get("m-in").getContent());
    }

    @Test public void legacyBundleCountsANewIncomingMessageAndDropsTheMatchingOutboxRow() {
        conversations.add(new ConversationWatermark("c0", 900));
        queued.add("A:mine-client");
        queued.add("B:mine-client");
        String incoming = message("c0", 1).replace("\"id\":\"m-c0\"", "\"id\":\"m-in\"")
                .replace("\"seq\":1,", "\"seq\":11,");
        String own = message("c0", 2).replace("\"id\":\"m-c0\"", "\"id\":\"m-own\"")
                .replace("\"seq\":1,", "\"seq\":12,")
                .replace("\"senderId\":\"peer\"", "\"senderId\":\"A\",\"clientMessageId\":\"mine-client\"");
        String deleted = message("c0", 3).replace("\"id\":\"m-c0\"", "\"id\":\"m-gone\"")
                .replace("\"seq\":1,", "\"seq\":13,")
                .replace("\"content\":\"v3\"", "\"content\":\"x\",\"deletedAt\":\"2026-11-12T00:00:03Z\"");
        String legacy = "{\"deltas\":[{\"conversationId\":\"c0\",\"newMessages\":["
                + incoming + "," + own + "," + deleted
                + "],\"hasMore\":false}],\"serverTime\":\"2026-11-12T00:00:01Z\"}";
        server = request -> request.getSyncVersion() != null
                ? "{\"syncVersion\":1,\"serverTime\":\"2026-11-12T00:00:00Z\",\"deltas\":[]}"
                : legacy;

        engine.sync().blockingAwait();

        assertEquals(Integer.valueOf(1), unreadCounts.get("c0"));
        assertFalse(queued.contains("A:mine-client"));
        assertTrue(queued.contains("B:mine-client"));
        engine.sync().blockingAwait();
        assertEquals(Integer.valueOf(1), unreadCounts.get("c0"));
    }

    @Test public void duplicateOwnAcknowledgementsClearOutboxEvenWhenContentIsNotApplied() {
        for (int scenario : new int[] {0, 1, 2}) {
            boolean versioned = scenario != 2;
            conversations.clear(); rows.clear(); cursors.clear(); queued.clear(); restart();
            conversations.add(new ConversationWatermark("c0", 900));
            MessageEntity cached = new MessageEntity("m-c0", "c0", 1, "A", "me", "TEXT",
                    versioned ? "newer edit" : "v1", "queued", null, null, null, 0, null,
                    versioned ? 3L : null, null);
            if (versioned) {
                cached = cached.withSyncVersion(3, 1);
                cursors.put("A:c0", new SyncCursorEntity("A", "c0", 1, 3,
                        "2026-11-12T00:00:00Z", null, null, false));
            }
            rows.put("m-c0", cached); // reconnect/history already cached the committed send
            queued.add("A:queued"); queued.add("B:queued");
            String wire = message("c0", scenario == 1 ? 3 : 1)
                    .replace("\"senderId\":\"peer\"", "\"senderId\":\"A\",\"clientMessageId\":\"queued\"");
            if (scenario == 1) wire = wire.replace("\"content\":\"v3\"", "\"content\":\"newer edit\"");
            if (!versioned) wire = wire.replace("\"changeVersion\":1,", "").replace("\"generation\":1,", "");
            engine.applyServerMessage(parseMessage(wire), "c0").blockingAwait();
            assertFalse("ack must clear its account queue even for an already-cached row", queued.contains("A:queued"));
            assertTrue("another account keeps its queued send", queued.contains("B:queued"));
            assertEquals(versioned ? "newer edit" : "v1", rows.get("m-c0").getContent());
        }
    }

    @Test public void trackerInvalidationCancelsHeldHttpWithoutWaitingForItsResponse() throws Exception {
        seed(1, 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        CountDownLatch invalidatorStarted = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean ownerReleased = new java.util.concurrent.atomic.AtomicBoolean();
        @SuppressWarnings("unchecked") Call<SyncResponse> held = mock(Call.class);
        when(held.execute()).thenAnswer(i -> {
            entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("fixture HTTP timeout"); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new java.io.IOException("fixture HTTP cancelled"); }
            return Response.success(parseResponse(envelope(List.of(delta("c0", 2, false, message("c0", 2))), 2)));
        });
        doAnswer(i -> { if (!ownerReleased.get()) cancelled.countDown(); release.countDown(); return null; }).when(held).cancel();
        doReturn(held).when(api).syncDeltas(any());
        java.util.concurrent.ExecutorService invalidator = java.util.concurrent.Executors.newSingleThreadExecutor();
        List<Throwable> undeliverable = new java.util.concurrent.CopyOnWriteArrayList<>();
        io.reactivex.rxjava3.plugins.RxJavaPlugins.setErrorHandler(undeliverable::add);
        TestObserver<Void> running = engine.sync().test();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            java.util.concurrent.Future<?> invalidation = invalidator.submit(() -> {
                invalidatorStarted.countDown(); tracker.invalidate();
            });
            assertTrue(invalidatorStarted.await(5, TimeUnit.SECONDS));
            try { invalidation.get(2, TimeUnit.SECONDS); }
            catch (java.util.concurrent.TimeoutException blocked) {
                fail("tracker invalidation is blocked behind held sync HTTP");
            }
            assertTrue("invalidation must cancel HTTP before the test releases it", cancelled.await(2, TimeUnit.SECONDS));
            assertEquals("v1", rows.get("m-c0").getContent());
        } finally {
            ownerReleased.set(true); release.countDown(); running.dispose();
            invalidator.shutdownNow(); invalidator.awaitTermination(5, TimeUnit.SECONDS);
            io.reactivex.rxjava3.plugins.RxJavaPlugins.setErrorHandler(null);
        }
        assertTrue("cancellation must not emit undeliverable failures", undeliverable.isEmpty());
    }

    @Test public void reversedIndependentReactionPagesSurviveLaterMessagesAndRestart() {
        conversations.add(new ConversationWatermark("c0", 900));
        cursors.put("A:c0", new SyncCursorEntity("A", "c0", 1, 0,
                "2026-11-12T00:00:00Z", null, null, false));
        AtomicInteger responses = new AtomicInteger();
        server = request -> {
            long after = cursor(request.getWatermarks().get(0));
            List<String> changes = new ArrayList<>();
            List<String> updates = new ArrayList<>();
            for (int n = (int) after + 1; n <= Math.min(100, after + 50); n++) {
                changes.add(message("c0", n).replace("m-c0", "m-" + n).replace("\"seq\":1,", "\"seq\":" + n + ","));
                int reactionMessage = 101 - n;
                updates.add("{\"messageId\":\"m-" + reactionMessage + "\",\"reactions\":[{\"emoji\":\"x\",\"count\":1,\"reactedByMe\":false}]}");
            }
            long next = Math.min(100, after + 50);
            return envelope(List.of(page("c0", next, 100, next < 100, next < 100, 1, String.join(",", changes))
                    .replace("\"reset\":false", "\"reset\":false,\"reactionCursor\":" + (next < 100 ? "50" : "null")
                            + ",\"reactionUpdates\":[" + String.join(",", updates) + "]")), responses.incrementAndGet() * 10);
        };
        engine.sync().blockingAwait();
        restart(); engine.sync().blockingAwait(); // no old events are returned after checkpoint completion
        assertEquals(100, rows.size());
        assertEquals("no aggregate may be dropped before its message arrives", 100, reactionRows.size());
        for (int n = 1; n <= 100; n++) assertEquals(1, reactionRows.get("m-" + n).get(0).getCount());
    }

    @SuppressWarnings("unchecked") private String omitDeltaField(String json, String field) {
        try {
            Map<String, Object> root = moshi.adapter(Map.class).fromJson(json);
            ((Map<String, Object>) ((List<?>) root.get("deltas")).get(0)).remove(field);
            return moshi.adapter(Map.class).toJson(root);
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static String page(String conversation, long cursor, long upper, boolean changeMore,
            boolean more, long generation, String changes) {
        return "{\"conversationId\":\"" + conversation + "\",\"changes\":[" + changes
                + "],\"changeCursor\":" + cursor + ",\"changeUpperBound\":" + upper
                + ",\"generation\":" + generation + ",\"changeHasMore\":" + changeMore
                + ",\"hasMore\":" + more + ",\"reset\":false}";
    }

    private void seed(long cursor, long generation) {
        conversations.add(new ConversationWatermark("c0", 900));
        rows.put("m-c0", new MessageEntity("m-c0", "c0", 1, "peer", "peer", "TEXT", "v" + cursor,
                null, null, null, null, 0, null, null, null).withSyncVersion(cursor, generation));
        cursors.put("A:c0", new SyncCursorEntity("A", "c0", generation, cursor,
                "2026-11-12T00:00:00Z", null, null, false));
    }

    private MessageDto parseMessage(String json) {
        try { return moshi.adapter(MessageDto.class).fromJson(json); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    private SyncResponse parseResponse(String json) {
        try { return moshi.adapter(SyncResponse.class).fromJson(json); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }

    private static final class SimulatedCrash extends RuntimeException { }

    private void restart() {
        tracker = new SessionOperationTracker();
        engine = new SyncEngine(db, api, messages, inbox, reactions, tokens, moshi,
                refresher, tracker);
    }

    private static ConversationEntity conversation(String id) {
        return new ConversationEntity(id, "DIRECT", null, 1L, "before", "peer", 0L,
                0, "peer", null, null, 0L, null, "peer", false,
                0L, false, null, false, false, false, null);
    }

    private long cursor(SyncRequest.Watermark watermark) {
        // Read the wire, so this test compiles on the pre-v2 base as well.
        try {
            Map<?, ?> json = moshi.adapter(Map.class).fromJson(
                    moshi.adapter(SyncRequest.Watermark.class).toJson(watermark));
            Object value = json.get("changeCursor");
            return value instanceof Number ? ((Number) value).longValue() : 0L;
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static String message(String conversation, long version) {
        return "{\"id\":\"m-" + conversation + "\",\"conversationId\":\"" + conversation
                + "\",\"seq\":1,\"senderId\":\"peer\",\"type\":\"TEXT\",\"content\":\"v" + version
                + "\",\"changeVersion\":" + version + ",\"generation\":1,\"reactions\":[],"
                + "\"createdAt\":\"2026-11-12T00:00:00Z\"}";
    }

    private static String delta(String conversation, long cursor, boolean more, String changes) {
        return "{\"conversationId\":\"" + conversation + "\",\"changes\":[" + changes
                + "],\"changeCursor\":" + cursor + ",\"changeUpperBound\":" + (more ? 65 : cursor) + ",\"generation\":1,"
                + "\"changeHasMore\":" + more + ",\"hasMore\":" + more + ",\"reset\":false}";
    }

    private static String envelope(List<String> deltas, int time) {
        return "{\"syncVersion\":2,\"deltas\":[" + String.join(",", deltas)
                + "],\"serverTime\":\"2026-11-12T00:" + String.format(Locale.ROOT, "%02d:%02d", time / 60, time % 60) + "Z\"}";
    }

    static String jwt(String userId) {
        return "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + userId + "\"}").getBytes(StandardCharsets.UTF_8)) + ".fixture";
    }

    @SuppressWarnings("unchecked") private static Call<SyncResponse> response(SyncResponse body) throws Exception {
        Call<SyncResponse> call = mock(Call.class);
        when(call.clone()).thenReturn(call);
        when(call.execute()).thenReturn(Response.success(body));
        return call;
    }
}
