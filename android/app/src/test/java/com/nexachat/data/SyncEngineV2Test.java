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
    private AppDatabase db;
    private MessageDao messages;
    private ConversationDao inbox;
    private ReactionDao reactions;
    private MessageApi api;
    private TokenStore tokens;
    private TokenRefresher refresher;
    private SyncEngine engine;
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
        PinDao pins = mock(PinDao.class);
        OutboxDao outbox = mock(OutboxDao.class);
        when(db.pinDao()).thenReturn(pins);
        when(db.outboxDao()).thenReturn(outbox);
        when(pins.deleteForConversation(anyString())).thenReturn(Completable.complete());
        when(outbox.deleteForConversation(anyString(), anyString())).thenReturn(Completable.complete());
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
        when(inbox.byId(anyString())).thenReturn(Maybe.empty());
        when(inbox.updateLastMessage(anyString(), anyLong(), anyString(), anyString(), anyLong()))
                .thenReturn(Completable.complete());
        when(inbox.updateLastMessageContent(anyString(), anyLong(), anyString()))
                .thenReturn(Completable.complete());
        when(inbox.deleteById(anyString())).thenReturn(Completable.complete());
        when(messages.deleteForConversation(anyString())).thenAnswer(i -> Completable.fromAction(() ->
                rows.values().removeIf(row -> row.getConversationId().equals(i.getArgument(0)))));
        when(reactions.deleteForConversation(anyString())).thenReturn(Completable.complete());
        doAnswer(i -> { ((Runnable) i.getArgument(0)).run(); return null; })
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

    private void restart() {
        engine = new SyncEngine(db, api, messages, inbox, reactions, tokens, moshi,
                refresher, new SessionOperationTracker());
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
