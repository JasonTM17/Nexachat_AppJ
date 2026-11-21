package com.nexachat.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexachat.core.database.AppDatabase;
import com.nexachat.core.database.ConversationDao;
import com.nexachat.core.database.ConversationEntity;
import com.nexachat.core.database.ConversationWatermark;
import com.nexachat.core.database.MessageDao;
import com.nexachat.core.database.MessageEntity;
import com.nexachat.core.database.MessageReactionEntity;
import com.nexachat.core.database.OutboxDao;
import com.nexachat.core.database.PinDao;
import com.nexachat.core.database.ReactionDao;
import com.nexachat.core.database.SyncCursorDao;
import com.nexachat.core.database.SyncCursorEntity;
import com.nexachat.core.network.MessageApi;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.core.network.dto.SyncRequest;
import com.nexachat.core.network.dto.SyncResponse;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.After;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;

/**
 * Regression for the fresh-install live-message wedge (chat-interactions and
 * offline-sync journeys failing after {@code pm clear}).
 *
 * <p>Production commits each delta page, live {@code MESSAGE_CREATED} event and
 * tombstone inside {@code AppDatabase.runInTransaction} on the single SQLCipher
 * writer connection. Issue #184's diagnosis: parking that held writer on a
 * queued Rx DAO statement ({@code byId().blockingGet()}, {@code
 * upsert().blockingAwait()}) creates hold-and-wait pressure — the queued
 * statement waits for the very connection the caller holds while the
 * fresh-install full-history sync drain saturates Room's shared query
 * executor, so every live write, the optimistic-send acknowledgement and the
 * chat render streams stop making progress and the journeys time out.
 *
 * <p>This harness models the SQLite invariant a mock cannot hide: the
 * transaction body runs while the writer connection is held, and every
 * Rx-queued DAO write executes on another connection that must first acquire
 * that writer. A write that cannot acquire it within the budget fails the test
 * with the #184 message instead of hanging; synchronous {@code
 * *InTransaction} statements run inline on the transaction's own session and
 * pass. The test fails on the pre-fix code (the live path parks the writer on
 * {@code upsert().blockingAwait()}) and passes once the body is synchronous.
 * Actual Room/SQLite proof remains the instrumented migration suite.
 */
public final class SyncPageStoreTransactionSafetyTest {
    private static final long PARK_BUDGET_SECONDS = 2L;
    private static final String OWNER = "A";
    private static final String CONVERSATION = "c1";

    private final Moshi moshi = new Moshi.Builder().build();
    private final WriterLockRoom room = new WriterLockRoom();

    @After public void tearDown() {
        room.shutdown();
    }

    private SyncEngine engine;
    private MessageApi api;

    /** In-memory state with the single-writer connection model. */
    private final class WriterLockRoom {
        final ReentrantLock writer = new ReentrantLock();
        final Map<String, MessageEntity> rows = new LinkedHashMap<>();
        final Map<String, SyncCursorEntity> cursors = new LinkedHashMap<>();
        final Map<String, ConversationEntity> conversations = new LinkedHashMap<>();
        final Map<String, Integer> unreadCounts = new LinkedHashMap<>();
        final Map<String, List<MessageReactionEntity>> reactionRows = new LinkedHashMap<>();
        final java.util.Set<String> queuedSends = new HashSet<>();
        final java.util.Set<String> pins = new HashSet<>();
        final Map<String, Boolean> deletedPreview = new LinkedHashMap<>();
        final Map<String, Boolean> muted = new LinkedHashMap<>();
        final List<String> lastMessageCalls = new ArrayList<>();
        final List<String> lastMessageContentCalls = new ArrayList<>();

        AppDatabase db = mock(AppDatabase.class);
        MessageDao messages = mock(MessageDao.class);
        ConversationDao inbox = mock(ConversationDao.class);
        ReactionDao reactions = mock(ReactionDao.class);
        PinDao pinDao = mock(PinDao.class);
        OutboxDao outbox = mock(OutboxDao.class);
        SyncCursorDao cursorDao = mock(SyncCursorDao.class);
        TokenStore tokens = mock(TokenStore.class);
        ExecutorService roomExecutor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "room-query-executor");
            thread.setDaemon(true);
            return thread;
        });

        private WriterLockRoom() {
            when(db.messageDao()).thenReturn(messages);
            when(db.conversationDao()).thenReturn(inbox);
            when(db.reactionDao()).thenReturn(reactions);
            when(db.pinDao()).thenReturn(pinDao);
            when(db.outboxDao()).thenReturn(outbox);
            when(db.syncCursorDao()).thenReturn(cursorDao);

            // The writer transaction: the body runs while the single writer
            // connection is held; a throw rolls the in-memory state back.
            doAnswer(invocation -> {
                writer.lock();
                Map<String, MessageEntity> rowsBefore = new LinkedHashMap<>(rows);
                Map<String, SyncCursorEntity> cursorsBefore = new LinkedHashMap<>(cursors);
                Map<String, Integer> unreadBefore = new LinkedHashMap<>(unreadCounts);
                Map<String, List<MessageReactionEntity>> reactionsBefore =
                        new LinkedHashMap<>(reactionRows);
                java.util.Set<String> queuedBefore = new java.util.HashSet<>(queuedSends);
                java.util.Set<String> pinsBefore = new java.util.HashSet<>(pins);
                try {
                    ((Runnable) invocation.getArgument(0)).run();
                    return null;
                } catch (Throwable failure) {
                    rows.clear(); rows.putAll(rowsBefore);
                    cursors.clear(); cursors.putAll(cursorsBefore);
                    unreadCounts.clear(); unreadCounts.putAll(unreadBefore);
                    reactionRows.clear(); reactionRows.putAll(reactionsBefore);
                    queuedSends.clear(); queuedSends.addAll(queuedBefore);
                    pins.clear(); pins.addAll(pinsBefore);
                    throw failure;
                } finally {
                    writer.unlock();
                }
            }).when(db).runInTransaction(any(Runnable.class));

            when(tokens.readTokens()).thenReturn(Maybe.just(
                    new Tokens(jwt(OWNER), Long.MAX_VALUE, "fixture")));
            when(tokens.readSyncServerTime(anyString())).thenReturn(Maybe.empty());
            when(tokens.saveSyncServerTime(anyString(), anyString()))
                    .thenReturn(Completable.complete());

            when(cursorDao.find(anyString(), anyString())).thenAnswer(invocation ->
                    cursors.get(invocation.getArgument(0) + ":" + invocation.getArgument(1)));
            doAnswer(invocation -> {
                SyncCursorEntity cursor = invocation.getArgument(0);
                cursors.put(cursor.ownerUserId + ":" + cursor.conversationId, cursor);
                return null;
            }).when(cursorDao).put(any(SyncCursorEntity.class));
            doAnswer(invocation -> {
                cursors.remove(invocation.getArgument(0) + ":" + invocation.getArgument(1));
                return null;
            }).when(cursorDao).delete(anyString(), anyString());

            // Queued Rx reads: WAL readers never wait for the writer, so a read
            // mock runs inline. The wedges under test all park on writes.
            when(messages.byId(anyString())).thenAnswer(invocation ->
                    Maybe.fromCallable(() -> rows.get(invocation.getArgument(0))));
            when(inbox.byId(anyString())).thenAnswer(invocation ->
                    Maybe.fromCallable(() -> conversations.get(invocation.getArgument(0))));
            // Synchronous reads run on the transaction's own session.
            when(messages.byIdInTransaction(anyString())).thenAnswer(invocation ->
                    rows.get(invocation.getArgument(0)));
            when(inbox.byIdSync(anyString())).thenAnswer(invocation ->
                    conversations.get(invocation.getArgument(0)));

            stubQueuedWrite(() -> when(messages.upsert(any(MessageEntity.class))),
                    invocation -> {
                        MessageEntity row = invocation.getArgument(0);
                        rows.putIfAbsent(row.getId(), row);
                        return null;
                    });
            stubQueuedWrite(() -> when(messages.upsertAll(anyList())),
                    invocation -> {
                        for (Object row : (List<?>) invocation.getArgument(0)) {
                            MessageEntity entity = (MessageEntity) row;
                            rows.putIfAbsent(entity.getId(), entity);
                        }
                        return null;
                    });
            stubQueuedWrite(() -> when(messages.update(any(MessageEntity.class))),
                    invocation -> {
                        MessageEntity row = invocation.getArgument(0);
                        rows.put(row.getId(), row);
                        return null;
                    });
            stubQueuedWrite(() -> when(messages.clearReplySnippets(anyString())),
                    invocation -> {
                        clearReplySnippetsState(invocation.getArgument(0));
                        return null;
                    });
            stubQueuedWrite(() -> when(inbox.updateLastMessage(anyString(), anyLong(),
                    anyString(), anyString(), anyLong(), anyBoolean(), any())),
                    invocation -> {
                        lastMessageCalls.add(invocation.getArgument(0));
                        return null;
                    });
            stubQueuedWrite(() -> when(inbox.updateLastMessageContent(anyString(), anyLong(),
                    anyString())),
                    invocation -> {
                        lastMessageContentCalls.add(invocation.getArgument(0));
                        return null;
                    });
            stubQueuedWrite(() -> when(inbox.incrementUnread(anyString(), anyInt())),
                    invocation -> {
                        unreadCounts.merge(invocation.getArgument(0),
                                invocation.getArgument(1), Integer::sum);
                        return null;
                    });
            stubQueuedWrite(() -> when(inbox.markLastMessageDeleted(anyString())),
                    invocation -> {
                        deletedPreview.put(invocation.getArgument(0), true);
                        return null;
                    });
            stubQueuedWrite(() -> when(inbox.updateMute(anyString(), anyBoolean(), any())),
                    invocation -> {
                        muted.put(invocation.getArgument(0), invocation.getArgument(1));
                        return null;
                    });
            stubQueuedWrite(() -> when(inbox.updateArchived(anyString(), anyBoolean())),
                    invocation -> {
                        return null;
                    });
            stubQueuedWrite(() -> when(inbox.updatePin(anyString(), anyBoolean(), any())),
                    invocation -> {
                        return null;
                    });
            stubQueuedWrite(() -> when(inbox.updateDisappearingSeconds(anyString(), anyInt())),
                    invocation -> {
                        return null;
                    });
            stubQueuedWrite(() -> when(pinDao.delete(anyString(), anyString())),
                    invocation -> {
                        pins.remove(invocation.getArgument(0) + ":" + invocation.getArgument(1));
                        return null;
                    });

            // Synchronous *InTransaction statements run on the caller's own
            // session inside the writer transaction: inline, lock reentrant.
            doAnswer(invocation -> {
                MessageEntity row = invocation.getArgument(0);
                rows.putIfAbsent(row.getId(), row);
                return null;
            }).when(messages).upsertInTransaction(any(MessageEntity.class));
            doAnswer(invocation -> {
                MessageEntity row = invocation.getArgument(0);
                rows.put(row.getId(), row);
                return null;
            }).when(messages).updateInTransaction(any(MessageEntity.class));
            doAnswer(invocation -> {
                for (Object row : (List<?>) invocation.getArgument(0)) {
                    MessageEntity entity = (MessageEntity) row;
                    rows.putIfAbsent(entity.getId(), entity);
                }
                return null;
            }).when(messages).upsertAllInTransaction(anyList());
            doAnswer(invocation -> {
                clearReplySnippetsState(invocation.getArgument(0));
                return null;
            }).when(messages).clearReplySnippetsInTransaction(anyString());
            doAnswer(invocation -> {
                unreadCounts.merge(invocation.getArgument(0),
                        invocation.getArgument(1), Integer::sum);
                return null;
            }).when(inbox).incrementUnreadInTransaction(anyString(), anyInt());
            doAnswer(invocation -> {
                deletedPreview.put(invocation.getArgument(0), true);
                return null;
            }).when(inbox).markLastMessageDeletedInTransaction(anyString());
            doAnswer(invocation -> {
                lastMessageCalls.add(invocation.getArgument(0));
                return null;
            }).when(inbox).updateLastMessageInTransaction(anyString(), anyLong(),
                    anyString(), anyString(), anyLong(), anyBoolean(), any());
            doAnswer(invocation -> {
                lastMessageContentCalls.add(invocation.getArgument(0));
                return null;
            }).when(inbox).updateLastMessageContentInTransaction(anyString(), anyLong(),
                    anyString());
            doAnswer(invocation -> {
                muted.put(invocation.getArgument(0), invocation.getArgument(1));
                return null;
            }).when(inbox).updateMuteInTransaction(anyString(), anyBoolean(), any());
            doAnswer(invocation -> {
                pins.remove(invocation.getArgument(0) + ":" + invocation.getArgument(1));
                return null;
            }).when(pinDao).deleteInTransaction(anyString(), anyString());
            doAnswer(invocation -> {
                queuedSends.remove(invocation.getArgument(0) + ":" + invocation.getArgument(1));
                return null;
            }).when(outbox).deleteInTransaction(anyString(), anyString());
            doAnswer(invocation -> {
                MessageEntity row = invocation.getArgument(0);
                rows.remove(row.getConversationId() + ":x");
                return null;
            }).when(messages).deleteForConversationInTransaction(anyString());
            doAnswer(invocation -> {
                reactionRows.remove(invocation.getArgument(0));
                return null;
            }).when(reactions).deleteForMessageInTransaction(anyString());
            doAnswer(invocation -> {
                reactionRows.put(invocation.getArgument(0),
                        new ArrayList<>((List<MessageReactionEntity>) invocation.getArgument(1)));
                return null;
            }).when(reactions).replaceForMessageInTransaction(anyString(), anyList());
            doAnswer(invocation -> {
                List<String> ids = invocation.getArgument(0);
                List<MessageReactionEntity> entities = invocation.getArgument(1);
                for (int index = 0; index < ids.size(); index++) {
                    reactionRows.put(ids.get(index),
                            entities.isEmpty() ? List.of() : List.of(entities.get(index)));
                }
                return null;
            }).when(reactions).replaceForMessagesInTransaction(anyList(), anyList());
        }

        /**
         * A queued Rx write the way Room actually runs one: the statement is
         * dispatched to a shared executor thread and, as a write, must first
         * acquire the single writer connection. While the test thread sits
         * inside the writer transaction that await()ed the statement, the
         * acquisition cannot succeed — the #184 hold-and-wait cycle. It fails
         * loudly within the budget instead of hanging the test.
         */
        @SuppressWarnings("unchecked")
        private <T> void stubQueuedWrite(
                java.util.function.Supplier<org.mockito.stubbing.OngoingStubbing<T>> stubbing,
                org.mockito.stubbing.Answer<Object> apply) {
            stubbing.get().thenAnswer(invocation -> queuedWrite(invocation, apply));
        }

        private Completable queuedWrite(
                org.mockito.invocation.InvocationOnMock invocation,
                org.mockito.stubbing.Answer<Object> apply) {
            String method = String.valueOf(invocation.getMethod());
            Future<Boolean> acquisition = roomExecutor.submit(() -> {
                if (!writer.tryLock(PARK_BUDGET_SECONDS, TimeUnit.SECONDS)) {
                    return Boolean.FALSE;
                }
                try {
                    apply.answer(invocation);
                    return Boolean.TRUE;
                } catch (Throwable applyFailure) {
                    throw new IllegalStateException(applyFailure);
                } finally {
                    writer.unlock();
                }
            });
            boolean acquired;
            try {
                acquired = acquisition.get(PARK_BUDGET_SECONDS + 1, TimeUnit.SECONDS);
            } catch (TimeoutException neverResolved) {
                acquired = false;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                acquired = false;
            } catch (java.util.concurrent.ExecutionException applyFailure) {
                acquired = false;
            }
            acquisition.cancel(true);
            if (!acquired) {
                return Completable.error(new IllegalStateException(
                        "queued Room write parked behind the writer transaction that "
                                + "awaited it (issue #184): " + method));
            }
            return Completable.complete();
        }

        private void clearReplySnippetsState(String messageId) {
            for (MessageEntity row : rows.values()) {
                if (messageId.equals(row.getReplyToId())) {
                    rows.put(row.getId(), row.withReplyToSnippet(null));
                }
            }
        }

        void shutdown() {
            roomExecutor.shutdownNow();
        }

        void seedFreshInstallState() {
            conversations.put(CONVERSATION, new ConversationEntity(
                    CONVERSATION, "DIRECT", null, 900L, "seed", "peer", 0L,
                    0, "peer", null, null, 0L, null, "peer", false,
                    0L, false, null, false, false, false, null));
            cursors.put(OWNER + ":" + CONVERSATION, new SyncCursorEntity(
                    OWNER, CONVERSATION, 1L, 5L, "2026-11-12T00:00:00Z", null, null, false));
        }
    }

    @Before public void setUp() {
        room.seedFreshInstallState();
        api = mock(MessageApi.class);
        when(api.syncDeltas(any(SyncRequest.class))).thenAnswer(invocation -> {
            SyncRequest request = invocation.getArgument(0);
            return responseCall(new SyncResponse(List.of(new SyncResponse.ConversationDeltaDto(
                    CONVERSATION, List.of(), List.of(), List.of(), List.of(), 900L, false)),
                    "2026-11-12T00:00:00Z"));
        });
        engine = new SyncEngine(room.db, api, room.messages, room.inbox, room.reactions,
                room.tokens, moshi);
    }

    @Test public void liveOwnEchoAcknowledgesSendWithoutParkingTheWriter() {
        room.queuedSends.add("cm-echo:" + OWNER);
        MessageDto echo = parseMessage(
                "{\"id\":\"m-echo\",\"conversationId\":\"" + CONVERSATION + "\",\"seq\":901,"
                        + "\"senderId\":\"" + OWNER + "\",\"senderDisplayName\":\"An\",\"type\":\"TEXT\","
                        + "\"content\":\"sj-ia-echo\",\"clientMessageId\":\"cm-echo\","
                        + "\"changeVersion\":6,\"generation\":1,\"createdAt\":\"2026-11-12T00:01:00Z\"}");

        engine.applyServerMessage(echo, CONVERSATION, false)
                .test()
                .awaitDone(3 * PARK_BUDGET_SECONDS, TimeUnit.SECONDS)
                .assertComplete();

        MessageEntity stored = room.rows.get("m-echo");
        assertNotNull("the acknowledged send must be stored in Room", stored);
        assertEquals("sj-ia-echo", stored.getContent());
        assertFalse("the optimistic outbox row must be retired by the acknowledgement",
                room.queuedSends.contains("cm-echo:" + OWNER));
        assertEquals("an own echo never raises the unread badge",
                0, (int) room.unreadCounts.getOrDefault(CONVERSATION, 0));
    }

    @Test public void livePeerMessageRendersAndCountsUnreadWithoutParkingTheWriter() {
        MessageDto incoming = parseMessage(
                "{\"id\":\"m-in\",\"conversationId\":\"" + CONVERSATION + "\",\"seq\":902,"
                        + "\"senderId\":\"peer\",\"senderDisplayName\":\"Binh\",\"type\":\"TEXT\","
                        + "\"content\":\"sj-os-burst\",\"changeVersion\":7,\"generation\":1,"
                        + "\"createdAt\":\"2026-11-12T00:02:00Z\"}");

        engine.applyServerMessage(incoming, CONVERSATION, true)
                .test()
                .awaitDone(3 * PARK_BUDGET_SECONDS, TimeUnit.SECONDS)
                .assertComplete();

        MessageEntity stored = room.rows.get("m-in");
        assertNotNull("the live peer message must be stored in Room", stored);
        assertEquals("sj-os-burst", stored.getContent());
        assertEquals("a background peer message raises the unread badge once",
                1, (int) room.unreadCounts.getOrDefault(CONVERSATION, 0));
    }

    @Test public void liveTombstoneCleansUpPinsAndPreviewWithoutParkingTheWriter() {
        MessageEntity existing = new MessageEntity("m-del", CONVERSATION, 5L, "peer", "peer",
                "TEXT", "gone", null, null, null, "quoted", 0, null, null, null)
                .withSyncVersion(5L, 1L);
        room.rows.put("m-del", existing);
        room.pins.add(CONVERSATION + ":m-del");
        room.conversations.put(CONVERSATION, new ConversationEntity(
                CONVERSATION, "DIRECT", null, 5L, "gone", "peer", 0L,
                0, "peer", null, null, 0L, null, "peer", false,
                0L, false, null, false, false, false, null));

        engine.applyServerDelete("m-del", CONVERSATION, 6L, 1L, 1_000L)
                .test()
                .awaitDone(3 * PARK_BUDGET_SECONDS, TimeUnit.SECONDS)
                .assertComplete();

        MessageEntity tombstone = room.rows.get("m-del");
        assertNotNull(tombstone);
        assertNotNull("the tombstone must keep its deletion timestamp",
                tombstone.getDeletedAtEpochMs());
        assertFalse("the tombstone's pin row is removed with it",
                room.pins.contains(CONVERSATION + ":m-del"));
        assertTrue("the deleted last message neutralises the inbox preview",
                room.deletedPreview.containsKey(CONVERSATION));
    }

    @Test public void v2PageCommitStoresNewRowsWithoutParkingTheWriter() {
        room.conversations.put(CONVERSATION, new ConversationEntity(
                CONVERSATION, "DIRECT", null, 900L, "seed", "peer", 0L,
                0, "peer", null, null, 0L, null, "peer", false,
                0L, false, null, false, false, false, null));
        room.cursors.remove(OWNER + ":" + CONVERSATION);

        MessageDto page = parseMessage(
                "{\"id\":\"m-page\",\"conversationId\":\"" + CONVERSATION + "\",\"seq\":903,"
                        + "\"senderId\":\"peer\",\"senderDisplayName\":\"Binh\",\"type\":\"TEXT\","
                        + "\"content\":\"sj-os-page\",\"changeVersion\":1,\"generation\":1,"
                        + "\"createdAt\":\"2026-11-12T00:03:00Z\"}");
        SyncResponse response = parseResponse(
                "{\"syncVersion\":2,\"serverTime\":\"2026-11-12T00:03:00Z\",\"deltas\":[{"
                        + "\"conversationId\":\"" + CONVERSATION + "\",\"changes\":["
                        + json(page)
                        + "],\"changeCursor\":1,\"changeUpperBound\":1,\"generation\":1,"
                        + "\"hasMore\":false,\"changeHasMore\":false,\"reset\":false,"
                        + "\"muted\":true,\"mutedUntil\":\"2026-11-13T00:00:00Z\"}]}");
        TokenRefresher refresher = new TokenRefresher(
                room.tokens, new SessionEvents(), () -> null);
        refresher.onSessionEstablished(1L);
        SyncPageStore store = new SyncPageStore(room.db, refresher, room.tokens, moshi);

        List<String> pending = store.apply(OWNER, 1L,
                Map.of(CONVERSATION, store.read(OWNER, CONVERSATION)), response);

        assertNotNull(room.rows.get("m-page"));
        assertEquals("sj-os-page", room.rows.get("m-page").getContent());
        assertEquals(1, (int) room.unreadCounts.getOrDefault(CONVERSATION, 0));
        assertTrue("the page leaves a reaction-baseline continuation", pending.contains(CONVERSATION));
        long committedCursor = room.cursors.get(OWNER + ":" + CONVERSATION).changeCursor;
        assertEquals("the page commits its change cursor", 1L, committedCursor);
        assertEquals(Boolean.TRUE, room.muted.get(CONVERSATION));
    }

    @Test public void legacyDeltaDrainStoresNewRowsWithoutParkingTheWriter() {
        when(room.db.syncCursorDao()).thenReturn(null);
        room.conversations.put(CONVERSATION, new ConversationEntity(
                CONVERSATION, "DIRECT", null, 900L, "seed", "peer", 0L,
                0, "peer", null, null, 0L, null, "peer", false,
                0L, false, null, false, false, false, null));
        MessageDto legacy = new MessageDto("m-legacy", 904L, CONVERSATION, "peer", "Binh",
                "TEXT", "sj-os-legacy", "2026-11-12T00:04:00Z");
        when(room.inbox.getAllWatermarks()).thenReturn(
                Single.just(List.of(new ConversationWatermark(CONVERSATION, 900L))));
        when(room.messages.getAllMaxSeqs()).thenReturn(
                Single.just(List.of(new ConversationWatermark(CONVERSATION, 900L))));
        when(api.syncDeltas(any(SyncRequest.class))).thenAnswer(invocation -> responseCall(
                new SyncResponse(List.of(new SyncResponse.ConversationDeltaDto(
                        CONVERSATION, List.of(legacy), List.of(), List.of(), List.of(), 904L, false)),
                        "2026-11-12T00:04:00Z")));

        engine.sync()
                .test()
                .awaitDone(3 * PARK_BUDGET_SECONDS, TimeUnit.SECONDS)
                .assertComplete();

        MessageEntity stored = room.rows.get("m-legacy");
        assertNotNull("the legacy delta page must store its new message", stored);
        assertEquals("sj-os-legacy", stored.getContent());
        assertEquals(1, (int) room.unreadCounts.getOrDefault(CONVERSATION, 0));
    }

    private MessageDto parseMessage(String json) {
        try {
            return moshi.adapter(MessageDto.class).fromJson(json);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private SyncResponse parseResponse(String json) {
        try {
            return moshi.adapter(SyncResponse.class).fromJson(json);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private String json(MessageDto dto) {
        return moshi.adapter(MessageDto.class).toJson(dto);
    }

    private static Call<SyncResponse> responseCall(SyncResponse body) {
        @SuppressWarnings("unchecked")
        Call<SyncResponse> call = mock(Call.class);
        when(call.clone()).thenReturn(call);
        try {
            when(call.execute()).thenReturn(Response.success(body));
        } catch (java.io.IOException setupFailure) {
            throw new AssertionError(setupFailure);
        }
        return call;
    }

    private static String jwt(String userId) {
        return "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + userId + "\"}").getBytes(StandardCharsets.UTF_8)) + ".fixture";
    }
}
