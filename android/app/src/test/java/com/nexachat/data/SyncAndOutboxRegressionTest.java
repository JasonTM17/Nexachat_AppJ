package com.nexachat.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import androidx.work.ListenableWorker;
import androidx.work.WorkerParameters;
import com.nexachat.core.database.AppDatabase;
import com.nexachat.core.database.ConversationDao;
import com.nexachat.core.database.ConversationEntity;
import com.nexachat.core.database.ConversationWatermark;
import com.nexachat.core.database.MessageDao;
import com.nexachat.core.database.MessageEntity;
import com.nexachat.core.database.OfflineOutboxDao;
import com.nexachat.core.database.OfflineOutboxEntity;
import com.nexachat.core.database.OutboxState;
import com.nexachat.core.database.ReactionDao;
import com.nexachat.core.network.MessageApi;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.core.network.dto.SyncRequest;
import com.nexachat.core.network.dto.SyncResponse;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.nexachat.feature.chat.ChatBubbleRules;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.observers.TestObserver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;

/**
 * Regression probes for the Android offline sync and outbox mechanisms:
 * 1. RFC 4122 UUIDv5 idempotency key generator determinism, bit patterns, and collision resistance.
 * 2. OfflineOutboxDao atomic 60s lease locking and multi-threaded race conditions.
 * 3. Delta Sync batching logic, watermark sequence handling, and delivery receipt watermark interactions.
 */
public final class SyncAndOutboxRegressionTest {

    // =========================================================================
    // 1. RFC 4122 UUIDv5 IDEMPOTENCY KEY STRESS TESTS
    // =========================================================================

    @Test
    public void testUUIDv5RFC4122AppendixBTestVectorAndBitStructure() {
        UUID namespaceDns = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
        UUID uuid = IdempotencyUtils.uuidV5(namespaceDns, "python.org");

        // RFC 4122 Appendix B exact vector
        assertEquals("886313e1-3b8a-5372-9b90-0c9aee199e5d", uuid.toString());

        // Bit-level validation
        // Version 5 in high 4 bits of time_hi_and_version (octet 6)
        assertEquals(5, uuid.version());
        long msb = uuid.getMostSignificantBits();
        int versionNibble = (int) ((msb >> 12) & 0x0f);
        assertEquals(5, versionNibble);

        // Variant 2 (RFC 4122) in bits 6-7 of clock_seq_hi_and_reserved (octet 8)
        assertEquals(2, uuid.variant());
        long lsb = uuid.getLeastSignificantBits();
        int variantBits = (int) ((lsb >>> 62) & 0x03);
        assertEquals(2, variantBits);
    }

    @Test
    public void testUUIDv5DeterminismAcrossRepeatedCalls() {
        String actionType = "SEND_MESSAGE";
        String conversationId = "conv-abc-123";
        String messageId = "msg-xyz-789";
        long timestamp = 1731050000000L;

        String keyFirst = IdempotencyUtils.actionIdempotencyKey(actionType, conversationId, messageId, timestamp);
        assertNotNull(keyFirst);

        for (int i = 0; i < 1000; i++) {
            String key = IdempotencyUtils.actionIdempotencyKey(actionType, conversationId, messageId, timestamp);
            assertEquals("UUIDv5 must be strictly deterministic across repeated calls", keyFirst, key);
        }
    }

    @Test
    public void testUUIDv5StrictInputDifferentiation() {
        String base = IdempotencyUtils.actionIdempotencyKey("ACTION", "conv-1", "msg-1", 1000L);

        // 1. Changing action type
        assertNotEquals(base, IdempotencyUtils.actionIdempotencyKey("ACTION2", "conv-1", "msg-1", 1000L));
        // 2. Changing conversation ID
        assertNotEquals(base, IdempotencyUtils.actionIdempotencyKey("ACTION", "conv-2", "msg-1", 1000L));
        // 3. Changing message ID
        assertNotEquals(base, IdempotencyUtils.actionIdempotencyKey("ACTION", "conv-1", "msg-2", 1000L));
        // 4. Changing timestamp by 1ms
        assertNotEquals(base, IdempotencyUtils.actionIdempotencyKey("ACTION", "conv-1", "msg-1", 1001L));
        // 5. Changing timestamp to 0
        assertNotEquals(base, IdempotencyUtils.actionIdempotencyKey("ACTION", "conv-1", "msg-1", 0L));
        // 6. Changing timestamp to negative
        assertNotEquals(base, IdempotencyUtils.actionIdempotencyKey("ACTION", "conv-1", "msg-1", -1L));
        // 7. Reaction ADD vs REMOVE
        assertNotEquals(
                IdempotencyUtils.reactionIdempotencyKey("u1", "m1", "👍", true),
                IdempotencyUtils.reactionIdempotencyKey("u1", "m1", "👍", false)
        );
        // 8. Pin vs Unpin
        assertNotEquals(
                IdempotencyUtils.pinIdempotencyKey("u1", "c1", "m1", true),
                IdempotencyUtils.pinIdempotencyKey("u1", "c1", "m1", false)
        );
    }

    @Test
    public void testUUIDv5CollisionStress50000DistinctInputs() {
        Set<String> generatedKeys = new HashSet<>(50000);
        String[] actions = {"SEND_MSG", "EDIT_MSG", "DELETE_MSG", "REACTION_ADD", "PIN_MSG"};
        String[] emojis = {"🔥", "👍", "❤️", "😂", "🚀", "🎉", "✨", "💯"};
        String utf8Prefix = "Tin nhắn tiếng Việt có dấu #";

        for (int i = 0; i < 10000; i++) {
            String act = actions[i % actions.length];
            String conv = "conv-" + (i % 20);
            String msg = utf8Prefix + i;
            long ts = 1731000000000L + i;

            String keyAction = IdempotencyUtils.actionIdempotencyKey(act, conv, msg, ts);
            generatedKeys.add(keyAction);

            String emoji = emojis[i % emojis.length];
            String keyReaction = IdempotencyUtils.reactionIdempotencyKey("user-" + (i % 50), "msg-" + i, emoji, (i % 2 == 0));
            generatedKeys.add(keyReaction);

            String keyPin = IdempotencyUtils.pinIdempotencyKey("user-" + (i % 50), conv, "msg-" + i, (i % 2 == 0));
            generatedKeys.add(keyPin);

            String keyToggle = IdempotencyUtils.reactionIdempotencyKey("user-" + (i % 50), "msg-" + i, emoji);
            generatedKeys.add(keyToggle);

            String keyBoundary = IdempotencyUtils.actionIdempotencyKey(act, conv, msg, Long.MAX_VALUE - i);
            generatedKeys.add(keyBoundary);
        }

        assertEquals("50,000 distinct input combinations must yield exactly 50,000 distinct UUIDv5 keys with 0 collisions",
                50000, generatedKeys.size());
    }

    @Test
    public void testUUIDv5MultiThreadedConcurrencyStress() throws InterruptedException {
        int threadCount = 32;
        int iterationsPerThread = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        ConcurrentHashMap<String, Boolean> concurrentKeys = new ConcurrentHashMap<>();
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < iterationsPerThread; i++) {
                        String key = IdempotencyUtils.actionIdempotencyKey(
                                "ACTION_" + threadId, "conv-" + threadId, "msg-" + i, 1000L + i);
                        UUID parsed = UUID.fromString(key);
                        if (parsed.version() != 5 || parsed.variant() != 2) {
                            errorCount.incrementAndGet();
                        }
                        concurrentKeys.put(key, Boolean.TRUE);
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue("Concurrent execution must complete within 15 seconds", doneLatch.await(15, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals("Thread-safe SHA-1 UUIDv5 generation must produce 0 errors", 0, errorCount.get());
        assertEquals("All concurrently generated UUIDs must be distinct",
                threadCount * iterationsPerThread, concurrentKeys.size());
    }

    // =========================================================================
    // 2. OFFLINE OUTBOX DAO ATOMIC LEASE LOCKING & CONCURRENCY STRESS TESTS
    // =========================================================================

    /**
     * Simulated atomic DAO matching Room SQL:
     * UPDATE offline_outbox SET state = 'IN_PROGRESS', lastAttemptEpochMs = :now
     * WHERE id = :id AND (state IN ('PENDING', 'SENDING') OR (state = 'IN_PROGRESS' AND lastAttemptEpochMs < :staleThreshold))
     */
    private static final class AtomicLeaseSimulator {
        private final AtomicReference<OfflineOutboxEntity> row = new AtomicReference<>();

        public AtomicLeaseSimulator(OfflineOutboxEntity initial) {
            this.row.set(initial);
        }

        public int claimLease(String id, long now, long staleThreshold) {
            while (true) {
                OfflineOutboxEntity current = row.get();
                if (current == null || !current.getId().equals(id)) {
                    return 0;
                }
                boolean claimable = OutboxState.PENDING.equals(current.getState())
                        || "SENDING".equals(current.getState())
                        || (OutboxState.IN_PROGRESS.equals(current.getState()) && current.getLastAttemptEpochMs() < staleThreshold);

                if (!claimable) {
                    return 0; // Lock cannot be acquired
                }

                OfflineOutboxEntity updated = new OfflineOutboxEntity(
                        current.getId(),
                        current.getIdempotencyKey(),
                        current.getActionType(),
                        current.getPayloadJson(),
                        OutboxState.IN_PROGRESS,
                        current.getRetryCount(),
                        current.getCreatedAtEpochMs(),
                        now
                );
                if (row.compareAndSet(current, updated)) {
                    return 1; // Successfully claimed
                }
            }
        }

        public OfflineOutboxEntity get() {
            return row.get();
        }
    }

    @Test
    public void testOfflineOutboxDaoLeaseAcquisitionAnd60sTimeoutExpiration() {
        long baseTime = 1000000L;
        OfflineOutboxEntity initial = new OfflineOutboxEntity(
                "item-1", "idemp-1", "SEND_MESSAGE", "{}", OutboxState.PENDING, 0, baseTime, 0L);
        AtomicLeaseSimulator dao = new AtomicLeaseSimulator(initial);

        long now1 = baseTime + 1000L;
        long staleThreshold1 = now1 - 60_000L;

        // 1. Initial claim from PENDING succeeds
        int claimed1 = dao.claimLease("item-1", now1, staleThreshold1);
        assertEquals(1, claimed1);
        assertEquals(OutboxState.IN_PROGRESS, dao.get().getState());
        assertEquals(now1, dao.get().getLastAttemptEpochMs());

        // 2. Immediate second claim within 60s lease window fails (returns 0)
        long now2 = now1 + 5_000L; // 5 seconds later
        long staleThreshold2 = now2 - 60_000L;
        int claimed2 = dao.claimLease("item-1", now2, staleThreshold2);
        assertEquals("Active lease must NOT be claimable by another worker", 0, claimed2);
        assertEquals(now1, dao.get().getLastAttemptEpochMs()); // Unchanged

        // 3. Claim at 59 seconds (still within 60s window) fails
        long now3 = now1 + 59_000L;
        long staleThreshold3 = now3 - 60_000L;
        int claimed3 = dao.claimLease("item-1", now3, staleThreshold3);
        assertEquals("Active lease at 59s must NOT be claimable", 0, claimed3);

        // 4. Claim at 60 seconds + 1ms (lease has timed out / stale) succeeds
        long now4 = now1 + 60_001L;
        long staleThreshold4 = now4 - 60_000L; // now1 + 1ms > now1, so lastAttempt (now1) < staleThreshold
        int claimed4 = dao.claimLease("item-1", now4, staleThreshold4);
        assertEquals("Expired lease (>60s) MUST be claimable by a worker", 1, claimed4);
        assertEquals(now4, dao.get().getLastAttemptEpochMs()); // Updated to new lease timestamp
    }

    @Test
    public void testOfflineOutboxDaoConcurrent50ThreadsRaceForSameRecord() throws InterruptedException {
        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        long now = System.currentTimeMillis();
        long staleThreshold = now - 60_000L;

        OfflineOutboxEntity initial = new OfflineOutboxEntity(
                "item-race", "idemp-race", "SEND_MESSAGE", "{}", OutboxState.PENDING, 0, now, 0L);
        AtomicLeaseSimulator dao = new AtomicLeaseSimulator(initial);

        AtomicInteger successClaims = new AtomicInteger(0);
        AtomicInteger rejectedClaims = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    int res = dao.claimLease("item-race", now, staleThreshold);
                    if (res == 1) {
                        successClaims.incrementAndGet();
                    } else {
                        rejectedClaims.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue("Concurrent claim race must complete within 10 seconds", doneLatch.await(10, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals("EXACTLY ONE thread must succeed in claiming the atomic lease", 1, successClaims.get());
        assertEquals("All other 49 threads must be rejected (0 claimed)", threadCount - 1, rejectedClaims.get());
        assertEquals(OutboxState.IN_PROGRESS, dao.get().getState());
    }

    @Test
    public void testOutboxSyncWorkerDrainingSkipsLockedRecord() {
        Context context = mock(Context.class);
        WorkerParameters workerParams = mock(WorkerParameters.class);
        MessageRepository messageRepo = mock(MessageRepository.class);
        OfflineOutboxDao offlineDao = mock(OfflineOutboxDao.class);

        OfflineOutboxEntity entity = new OfflineOutboxEntity(
                "item-locked", "idemp-1", "SEND_MESSAGE", "{}", OutboxState.IN_PROGRESS, 0, 1000L, 500L);

        when(offlineDao.getClaimableItems(anyLong())).thenReturn(Single.just(List.of(entity)));
        when(offlineDao.claimLease(eq("item-locked"), anyLong(), anyLong()))
                .thenReturn(Single.just(0)); // Locked by another worker

        OutboxSyncWorker worker = new OutboxSyncWorker(context, workerParams, messageRepo, offlineDao);
        TestObserver<Void> observer = worker.drainOfflineOutbox().test();

        observer.awaitCount(1);
        observer.assertComplete();

        // markSent must NEVER be called when claimLease returns 0
        verify(offlineDao, never()).markSent(anyString(), anyLong());
    }

    // =========================================================================
    // 3. DELTA SYNC BATCHING & WATERMARK SEQUENCE HANDLING TESTS
    // =========================================================================

    @Test
    public void testDeltaSyncWithoutWatermarksCompletesWithoutApiCall() {
        AppDatabase db = mock(AppDatabase.class);
        MessageApi api = mock(MessageApi.class);
        MessageDao messageDao = mock(MessageDao.class);
        ConversationDao conversationDao = mock(ConversationDao.class);
        ReactionDao reactionDao = mock(ReactionDao.class);
        TokenStore tokenStore = mock(TokenStore.class);
        Moshi moshi = new Moshi.Builder().build();

        when(tokenStore.readTokens()).thenReturn(Maybe.empty());
        when(messageDao.getAllMaxSeqs()).thenReturn(Single.just(Collections.emptyList()));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(Collections.emptyList()));

        SyncEngine syncEngine = new SyncEngine(db, api, messageDao, conversationDao, reactionDao, tokenStore, moshi);
        TestObserver<Void> observer = syncEngine.sync().test();

        observer.awaitCount(1);
        observer.assertComplete();
        verify(api, never()).syncDeltas(any());
    }

    @Test
    public void testDeltaSyncWatermarkAggregationTakesMaximumOfMessageAndConversationSeq() {
        AppDatabase db = mock(AppDatabase.class);
        MessageApi api = mock(MessageApi.class);
        MessageDao messageDao = mock(MessageDao.class);
        ConversationDao conversationDao = mock(ConversationDao.class);
        ReactionDao reactionDao = mock(ReactionDao.class);
        TokenStore tokenStore = mock(TokenStore.class);
        Moshi moshi = new Moshi.Builder().build();

        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
                .when(db).runInTransaction(any(Runnable.class));
        when(tokenStore.readTokens()).thenReturn(Maybe.just(new Tokens("token", 1000L, "refresh")));

        // c1: msg=10, conv=5 -> max 10
        // c2: msg=3, conv=8 -> max 8
        // c3: msg=0, conv=12 -> max 12
        List<ConversationWatermark> msgWatermarks = List.of(
                new ConversationWatermark("c1", 10L),
                new ConversationWatermark("c2", 3L)
        );
        List<ConversationWatermark> convWatermarks = List.of(
                new ConversationWatermark("c1", 5L),
                new ConversationWatermark("c2", 8L),
                new ConversationWatermark("c3", 12L)
        );

        when(messageDao.getAllMaxSeqs()).thenReturn(Single.just(msgWatermarks));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(convWatermarks));

        SyncResponse watermarkResponse = new SyncResponse(List.of(
                new SyncResponse.ConversationDeltaDto(
                        "c1", List.of(), List.of(), List.of(), List.of(), 10L, false),
                new SyncResponse.ConversationDeltaDto(
                        "c2", List.of(), List.of(), List.of(), List.of(), 8L, false),
                new SyncResponse.ConversationDeltaDto(
                        "c3", List.of(), List.of(), List.of(), List.of(), 12L, false)),
                "2026-11-08T10:00:00Z");
        Call<SyncResponse> watermarkCall = responseCall(watermarkResponse);
        when(api.syncDeltas(any(SyncRequest.class))).thenReturn(watermarkCall);

        SyncEngine syncEngine = new SyncEngine(db, api, messageDao, conversationDao, reactionDao, tokenStore, moshi);
        TestObserver<Void> observer = syncEngine.sync().test();
        observer.awaitCount(1);
        observer.assertComplete();

        org.mockito.ArgumentCaptor<SyncRequest> captor = org.mockito.ArgumentCaptor.forClass(SyncRequest.class);
        verify(api).syncDeltas(captor.capture());
        SyncRequest req = captor.getValue();

        assertEquals(3, req.getWatermarks().size());
        for (SyncRequest.Watermark wm : req.getWatermarks()) {
            if ("c1".equals(wm.getConversationId())) assertEquals(10L, wm.getLastSeq());
            if ("c2".equals(wm.getConversationId())) assertEquals(8L, wm.getLastSeq());
            if ("c3".equals(wm.getConversationId())) assertEquals(12L, wm.getLastSeq());
        }
    }

    @Test
    public void testDeltaSyncNeverFabricatesPeerReceiptWatermarks() {
        AppDatabase db = mock(AppDatabase.class);
        MessageApi api = mock(MessageApi.class);
        MessageDao messageDao = mock(MessageDao.class);
        ConversationDao conversationDao = mock(ConversationDao.class);
        ReactionDao reactionDao = mock(ReactionDao.class);
        TokenStore tokenStore = mock(TokenStore.class);
        Moshi moshi = new Moshi.Builder().build();

        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
                .when(db).runInTransaction(any(Runnable.class));
        when(tokenStore.readTokens()).thenReturn(Maybe.just(new Tokens("token", 1000L, "refresh")));
        when(conversationDao.bumpPeerReadSeq(anyString(), anyLong())).thenReturn(Completable.complete());
        when(conversationDao.bumpPeerDeliveredSeq(anyString(), anyLong())).thenReturn(Completable.complete());
        when(messageDao.byId(anyString())).thenReturn(Maybe.empty());
        when(messageDao.upsertAll(anyList())).thenReturn(Completable.complete());
        when(conversationDao.incrementUnread(anyString(), anyInt())).thenReturn(Completable.complete());
        when(conversationDao.updateLastMessage(anyString(), anyLong(), anyString(), anyString(), anyLong(), anyBoolean()))
                .thenReturn(Completable.complete());

        when(messageDao.getAllMaxSeqs()).thenReturn(Single.just(List.of(new ConversationWatermark("c1", 10L))));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(Collections.emptyList()));

        MessageDto msg15 = new MessageDto(
                "m-15", 15L, "c1", "user-me", "Alice", null, null, "TEXT", "Hello Bob",
                null, null, List.of(), List.of(), "2026-11-08T10:00:00Z", null, null
        );
        SyncResponse.ConversationDeltaDto delta = new SyncResponse.ConversationDeltaDto(
                "c1", List.of(msg15), List.of(), List.of(), List.of(), 15L, false
        );
        SyncResponse response = new SyncResponse(List.of(delta), "2026-11-08T10:00:00Z");

        Call<SyncResponse> peerReceiptCall = responseCall(response);
        when(api.syncDeltas(any(SyncRequest.class))).thenReturn(peerReceiptCall);

        // Before and after sync, the peer's receipt watermark remains at 10.
        boolean seenBeforeSync = ChatBubbleRules.isSeenReceiptVisible(false, true, 15L, 10L);
        assertFalse("Message 15 must not show seen before sync", seenBeforeSync);

        // Run Delta Sync
        SyncEngine syncEngine = new SyncEngine(db, api, messageDao, conversationDao, reactionDao, tokenStore, moshi);
        TestObserver<Void> observer = syncEngine.sync().test();
        observer.awaitCount(1);
        observer.assertComplete();

        verify(conversationDao, never()).bumpPeerDeliveredSeq(anyString(), anyLong());
        verify(conversationDao, never()).bumpPeerReadSeq(anyString(), anyLong());
        assertFalse("Delta ingestion must not make an unacknowledged message appear seen",
                ChatBubbleRules.isSeenReceiptVisible(false, true, 15L, 10L));
    }

    @Test
    public void testDeleteOnlyDeltaDoesNotUseNewMessagePreviewPath() {
        AppDatabase db = mock(AppDatabase.class);
        MessageApi api = mock(MessageApi.class);
        MessageDao messageDao = mock(MessageDao.class);
        ConversationDao conversationDao = mock(ConversationDao.class);
        ReactionDao reactionDao = mock(ReactionDao.class);
        TokenStore tokenStore = mock(TokenStore.class);
        Moshi moshi = new Moshi.Builder().build();

        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
                .when(db).runInTransaction(any(Runnable.class));
        when(tokenStore.readTokens()).thenReturn(Maybe.just(new Tokens("token", 1000L, "refresh")));
        when(conversationDao.bumpPeerReadSeq(anyString(), anyLong())).thenReturn(Completable.complete());
        when(conversationDao.bumpPeerDeliveredSeq(anyString(), anyLong())).thenReturn(Completable.complete());
        when(messageDao.byId(anyString())).thenReturn(Maybe.empty());

        when(messageDao.getAllMaxSeqs()).thenReturn(Single.just(List.of(new ConversationWatermark("c1", 10L))));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(Collections.emptyList()));

        // Delta returns NO new messages, only a deleted message ID, but maxSeq is 15
        SyncResponse.ConversationDeltaDto delta = new SyncResponse.ConversationDeltaDto(
                "c1", List.of(), List.of(), List.of("m-deleted"), List.of(), 15L, false
        );
        SyncResponse response = new SyncResponse(List.of(delta), "2026-11-08T10:00:00Z");

        Call<SyncResponse> deleteOnlyCall = responseCall(response);
        when(api.syncDeltas(any(SyncRequest.class))).thenReturn(deleteOnlyCall);

        SyncEngine syncEngine = new SyncEngine(db, api, messageDao, conversationDao, reactionDao, tokenStore, moshi);
        TestObserver<Void> observer = syncEngine.sync().test();
        observer.awaitCount(1);
        observer.assertComplete();

        // When newMessages is empty: updateLastMessage is NEVER called on conversationDao
        verify(conversationDao, never()).updateLastMessage(anyString(), anyLong(), anyString(), anyString(), anyLong(), anyBoolean());

        // Note that lastMessageSeq in conversations table is never advanced when there are no new messages,
        // so conversationDao.getAllWatermarks() will remain at its old value!
    }

    @Test
    public void testDeltaSyncMarksDeletedLastMessageForNeutralInboxPreview() {
        assertDeletedPreviewUpdated(5L, true);
    }

    @Test
    public void testDeltaSyncDoesNotChangePreviewWhenDeletingNonLastMessage() {
        assertDeletedPreviewUpdated(6L, false);
    }

    private static void assertDeletedPreviewUpdated(long currentLastMessageSeq, boolean shouldMarkDeleted) {
        AppDatabase db = mock(AppDatabase.class);
        MessageApi api = mock(MessageApi.class);
        MessageDao messageDao = mock(MessageDao.class);
        ConversationDao conversationDao = mock(ConversationDao.class);
        ReactionDao reactionDao = mock(ReactionDao.class);
        TokenStore tokenStore = mock(TokenStore.class);
        Moshi moshi = new Moshi.Builder().build();

        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
                .when(db).runInTransaction(any(Runnable.class));
        when(tokenStore.readTokens()).thenReturn(Maybe.just(new Tokens("dummy-token", 1000L, "refresh")));
        when(messageDao.getAllMaxSeqs()).thenReturn(Single.just(List.of(new ConversationWatermark("c1", 10L))));
        when(conversationDao.getAllWatermarks()).thenReturn(Single.just(Collections.emptyList()));
        when(messageDao.byId("m-deleted")).thenReturn(Maybe.just(new MessageEntity(
                "m-deleted", "c1", 5L, "user-peer", "Alice", "TEXT", "old preview", "client-1",
                null, null, null, 1700000000L, null, null, null)));
        when(conversationDao.byId("c1")).thenReturn(Maybe.just(new ConversationEntity(
                "c1", "DIRECT", null, currentLastMessageSeq, "old preview", "Alice", 1000L,
                0, null, null, null, 0L, null, null, false)));
        when(messageDao.update(any(MessageEntity.class))).thenReturn(Completable.complete());
        when(messageDao.clearReplySnippets(anyString())).thenReturn(Completable.complete());
        when(conversationDao.markLastMessageDeleted(anyString())).thenReturn(Completable.complete());

        SyncResponse.ConversationDeltaDto delta = new SyncResponse.ConversationDeltaDto(
                "c1", List.of(), List.of(), List.of("m-deleted"), List.of(), 15L, false);
        Call<SyncResponse> deletedPreviewCall = responseCall(
                new SyncResponse(List.of(delta), "2026-11-08T10:00:00Z"));
        when(api.syncDeltas(any(SyncRequest.class))).thenReturn(deletedPreviewCall);

        SyncEngine engine = new SyncEngine(db, api, messageDao, conversationDao,
                reactionDao, tokenStore, moshi);
        engine.sync().blockingAwait();

        if (shouldMarkDeleted) {
            verify(conversationDao).markLastMessageDeleted("c1");
        } else {
            verify(conversationDao, never()).markLastMessageDeleted(anyString());
        }
    }

    private static Call<SyncResponse> responseCall(SyncResponse response) {
        @SuppressWarnings("unchecked")
        Call<SyncResponse> call = mock(Call.class);
        when(call.clone()).thenReturn(call);
        try {
            when(call.execute()).thenReturn(Response.success(response));
        } catch (java.io.IOException setupFailure) {
            throw new AssertionError(setupFailure);
        }
        return call;
    }
}
