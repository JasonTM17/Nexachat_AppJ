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
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;

/**
 * Issue #270 contract: the first inbox page persist of a session re-arms
 * exactly one epoch-guarded sync drain, the drain that the startup reconnect
 * race may have emptied; later persists in the same epoch coalesce away, and
 * an epoch change between the persist signal and the armed trigger suppresses
 * the call. Fixture follows {@code SyncEngineV2Test} with a trampoline io
 * scheduler so each armed flight runs inline on the calling thread.
 */
public final class SyncEnginePostPersistRetriggerTest {
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
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
        db = mock(AppDatabase.class);
        messages = mock(MessageDao.class);
        inbox = mock(ConversationDao.class);
        reactions = mock(ReactionDao.class);
        api = mock(MessageApi.class);
        tokens = mock(TokenStore.class);
        when(tokens.readTokens()).thenReturn(Maybe.just(new Tokens(jwt("A"), Long.MAX_VALUE, "fixture")));
        when(tokens.readSyncServerTime(anyString())).thenReturn(Maybe.empty());
        when(tokens.saveSyncServerTime(anyString(), anyString())).thenReturn(Completable.complete());
        when(tokens.reserveSessionClosing(anyLong())).thenReturn(Single.just(2L));
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
        MessageTranslationDao translations = mock(MessageTranslationDao.class);
        when(db.messageTranslationDao()).thenReturn(translations);
        when(translations.deleteForConversation(anyString())).thenReturn(Completable.complete());
        when(pins.deleteForConversation(anyString())).thenReturn(Completable.complete());
        when(pins.delete(anyString(), anyString())).thenReturn(Completable.complete());
        when(outbox.deleteForConversation(anyString(), anyString())).thenReturn(Completable.complete());
        doAnswer(i -> { queued.remove(i.getArgument(1) + ":" + i.getArgument(0)); return null;
        }).when(outbox).deleteInTransaction(anyString(), anyString());
        when(inbox.getAllWatermarks()).thenAnswer(i -> Single.just(new ArrayList<>(conversations)));
        when(messages.getAllMaxSeqs()).thenReturn(Single.just(List.of()));
        when(messages.byId(anyString())).thenAnswer(i -> Maybe.fromCallable(() -> rows.get(i.getArgument(0))));
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
        doAnswer(i -> {
            rows.values().removeIf(row -> row.getConversationId().equals(i.getArgument(0)));
            return null;
        }).when(messages).deleteForConversationInTransaction(anyString());
        when(reactions.deleteForConversation(anyString())).thenReturn(Completable.complete());
        doAnswer(i -> { reactionRows.put(i.getArgument(0), new ArrayList<>(i.getArgument(1))); return null;
        }).when(reactions).replaceForMessageInTransaction(anyString(), anyList());
        doAnswer(i -> { reactionRows.remove(i.getArgument(0)); return null;
        }).when(reactions).deleteForMessageInTransaction(anyString());
        // Synchronous *InTransaction statements inside runInTransaction (issue #184):
        // page commits and live lifecycle writes no longer park on queued Rx calls.
        when(messages.byIdInTransaction(anyString())).thenAnswer(i -> rows.get(i.getArgument(0)));
        doAnswer(i -> { MessageEntity row = i.getArgument(0); rows.putIfAbsent(row.getId(), row); return null;
        }).when(messages).upsertInTransaction(any(MessageEntity.class));
        doAnswer(i -> { MessageEntity row = i.getArgument(0); rows.put(row.getId(), row); return null;
        }).when(messages).updateInTransaction(any(MessageEntity.class));
        doAnswer(i -> {
            for (MessageEntity row : (List<MessageEntity>) i.getArgument(0)) rows.putIfAbsent(row.getId(), row);
            return null;
        }).when(messages).upsertAllInTransaction(anyList());
        when(inbox.byIdSync(anyString())).thenAnswer(i ->
                conversations.stream().anyMatch(c -> c.getConversationId().equals(i.getArgument(0)))
                        ? conversation(i.getArgument(0)) : null);
        doAnswer(i -> { unreadCounts.merge(i.getArgument(0), i.getArgument(1), Integer::sum); return null;
        }).when(inbox).incrementUnreadInTransaction(anyString(), anyInt());
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
        server = request -> envelope(List.of(delta("c0", 1, false,
                cursor(request.getWatermarks().get(0)) == 0 ? message("c0", 1) : "")), 1);
        restart();
    }

    @After public void tearDown() {
        RxJavaPlugins.reset();
    }

    @Test public void firstPersistCompletionFiresExactlyOneEpochGuardedSync() {
        // The persist landed its rows: the watermark table now has c0, the
        // state the racing startup sync read as empty.
        conversations.add(new ConversationWatermark("c0", 900L));

        engine.onInboxPagePersistCompleted();

        assertEquals("the first persist completion armed one re-trigger",
                1, engine.postPersistSyncsFired.get());
        // One drain on a fresh cursor is two requests: the change page plus
        // the baseline-replay epilogue SyncPageStore forces to clear
        // rebuildingReactions.
        assertEquals("the re-trigger ran one real drain", 2, requests.size());
        assertEquals("the drain fetched the burst row", "v1", rows.get("m-c0").getContent());
        assertEquals("the drain advanced the durable cursor", 1, cursors.get("A:c0").changeCursor);
    }

    @Test public void subsequentPersistsInTheSameEpochDoNotRetrigger() {
        conversations.add(new ConversationWatermark("c0", 900L));

        engine.onInboxPagePersistCompleted();
        engine.onInboxPagePersistCompleted();
        engine.onInboxPagePersistCompleted();

        assertEquals("later pages in the same session coalesce into the one armed flight",
                1, engine.postPersistSyncsFired.get());
        assertEquals("no second drain fights the first", 2, requests.size());
    }

    @Test public void epochChangeBetweenPersistAndTriggerSuppressesTheCall() {
        conversations.add(new ConversationWatermark("c0", 900L));
        // A session boundary lands inside the armed flight, between the
        // persist signal and the trigger's epoch guard.
        engine.retriggerTestHookBeforeGuard = () -> refresher.onSessionEstablished(2L);

        engine.onInboxPagePersistCompleted();

        assertEquals("a stale session's armed trigger is suppressed",
                0, engine.postPersistSyncsFired.get());
        assertEquals("the suppressed trigger never reached the server", 0, requests.size());
        assertTrue("no page of the stale session was applied", rows.isEmpty());

        // The new session keeps its own first-persist allowance.
        engine.retriggerTestHookBeforeGuard = () -> { };
        engine.onInboxPagePersistCompleted();
        assertEquals("the new epoch's first persist drains normally",
                1, engine.postPersistSyncsFired.get());
        assertEquals(2, requests.size());
        assertEquals("v1", rows.get("m-c0").getContent());
    }

    @Test public void fencedOutSessionNeitherFiresNorConsumesTheAllowance() {
        conversations.add(new ConversationWatermark("c0", 900L));
        refresher.onLogout();

        engine.onInboxPagePersistCompleted();

        assertEquals("a dying session's persist signal never fires a sync",
                0, engine.postPersistSyncsFired.get());
        assertEquals(0, requests.size());

        refresher.onSessionEstablished(3L);
        engine.onInboxPagePersistCompleted();
        assertEquals("the allowance was not consumed by the closed session",
                1, engine.postPersistSyncsFired.get());
        assertEquals(2, requests.size());
    }

    @Test public void retriggeredDrainIsANormalDrainAndStillSuppressesPrunes() {
        conversations.add(new ConversationWatermark("c0", 900L));
        engine.onInboxPagePersistCompleted();
        assertEquals(2, requests.size());

        // The re-triggered flight opened and closed the probe window like
        // every drain; nothing latched open (issue #266 invariants).
        assertFalse(engine.drainProbe.isDrainActive());
        assertFalse("a completed re-triggered drain is not pending continuation",
                engine.hasPendingContinuation());
        assertEquals(1, cursors.get("A:c0").changeCursor);
    }

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
