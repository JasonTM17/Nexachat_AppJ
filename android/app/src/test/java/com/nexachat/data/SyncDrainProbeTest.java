package com.nexachat.data;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import com.nexachat.core.database.*;
import com.nexachat.core.network.*;
import com.nexachat.core.network.dto.*;
import com.nexachat.core.network.stomp.ChatEvent;
import com.nexachat.core.network.stomp.RealtimeSession;
import com.nexachat.core.storage.*;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.*;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.processors.PublishProcessor;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import retrofit2.Call;
import retrofit2.Response;

/**
 * Counters and hook invocation for the phase-0 drain instrumentation seam
 * (issue #266): page fetch/apply split, guard vs delta-loop split, drain
 * totals, restart counting, and observer emission counting.
 */
public final class SyncDrainProbeTest {
    private final Moshi moshi = new Moshi.Builder().build();
    private final Map<String, MessageEntity> rows = new LinkedHashMap<>();
    private final List<ConversationWatermark> conversations = new ArrayList<>();
    private final Map<String, SyncCursorEntity> cursors = new LinkedHashMap<>();
    private final Map<String, List<MessageReactionEntity>> reactionRows = new LinkedHashMap<>();
    private final Map<String, Integer> unreadCounts = new LinkedHashMap<>();
    private AppDatabase db;
    private MessageDao messages;
    private ConversationDao inbox;
    private ReactionDao reactions;
    private MessageApi api;
    private TokenStore tokens;
    private TokenRefresher refresher;
    private SyncEngine engine;
    private RecordingSink sink;
    private java.util.function.Function<SyncRequest, String> server;

    /** Drain lines captured by the sink the engine's probe is switched to. */
    private static final class RecordingSink implements SyncDrainProbe.LineSink {
        final List<String> lines = new CopyOnWriteArrayList<>();

        @Override public boolean wantsLines() { return true; }

        @Override public void accept(String line) { lines.add(line); }

        String drainLine() {
            for (String line : lines) if (line.startsWith("drain")) return line;
            fail("no drain totals line was emitted");
            return null;
        }
    }

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
        when(inbox.getAllWatermarks()).thenAnswer(i -> Single.just(new ArrayList<>(conversations)));
        // Synchronous *InTransaction variants used inside runInTransaction (issue #184).
        when(messages.byIdInTransaction(anyString())).thenAnswer(i -> rows.get(i.getArgument(0)));
        doAnswer(i -> { MessageEntity row = i.getArgument(0); rows.putIfAbsent(row.getId(), row); return null;
        }).when(messages).upsertInTransaction(any(MessageEntity.class));
        doAnswer(i -> { MessageEntity row = i.getArgument(0); rows.put(row.getId(), row); return null;
        }).when(messages).updateInTransaction(any(MessageEntity.class));
        doAnswer(i -> {
            rows.values().removeIf(row -> row.getConversationId().equals(i.getArgument(0)));
            return null;
        }).when(messages).deleteForConversationInTransaction(anyString());
        when(inbox.byIdSync(anyString())).thenAnswer(i ->
                conversations.stream().anyMatch(c -> c.getConversationId().equals(i.getArgument(0)))
                        ? conversation(i.getArgument(0)) : null);
        doAnswer(i -> { unreadCounts.merge(i.getArgument(0), i.getArgument(1), Integer::sum); return null;
        }).when(inbox).incrementUnreadInTransaction(anyString(), anyInt());
        doAnswer(i -> { reactionRows.remove(i.getArgument(0)); return null;
        }).when(reactions).deleteForConversationInTransaction(anyString());
        doAnswer(i -> { reactionRows.put(i.getArgument(0), new ArrayList<>(i.getArgument(1))); return null;
        }).when(reactions).replaceForMessageInTransaction(anyString(), anyList());
        // Transaction simulation with rollback so a guard abort leaves no partial state.
        doAnswer(i -> {
            Map<String, MessageEntity> beforeRows = new LinkedHashMap<>(rows);
            Map<String, SyncCursorEntity> beforeCursors = new LinkedHashMap<>(cursors);
            Map<String, List<MessageReactionEntity>> beforeReactions = new LinkedHashMap<>(reactionRows);
            Map<String, Integer> beforeUnread = new LinkedHashMap<>(unreadCounts);
            try { ((Runnable) i.getArgument(0)).run(); }
            catch (Throwable error) { rows.clear(); rows.putAll(beforeRows);
                cursors.clear(); cursors.putAll(beforeCursors);
                reactionRows.clear(); reactionRows.putAll(beforeReactions);
                unreadCounts.clear(); unreadCounts.putAll(beforeUnread); throw error; }
            return null;
        }).when(db).runInTransaction(any(Runnable.class));
        when(api.syncDeltas(any())).thenAnswer(i -> {
            SyncRequest request = i.getArgument(0);
            return response(moshi.adapter(SyncResponse.class).fromJson(server.apply(request)));
        });
        restart();
    }

    @After public void tearDown() {
        RxJavaPlugins.reset();
    }

    @Test public void pageSplitTimerRecordsFetchApplyGuardAndDeltaPerPage() {
        seed();
        server = request -> {
            long at = cursor(request.getWatermarks().get(0));
            if (at == 0) return envelope(List.of(delta("c0", 2, true, message("c0", 2))), 1);
            return envelope(List.of(delta("c0", 2, false, "")), 2);
        };

        engine.sync().onErrorComplete().blockingAwait();

        SyncDrainProbe probe = engine.drainProbe;
        assertEquals(2, probe.pages);
        assertEquals(0, probe.restarts);
        assertNull(probe.drainErrorClass);
        assertTrue("fetch/apply/guard/delta spans are all timed",
                probe.fetchNanos >= 0 && probe.applyNanos >= 0
                        && probe.guardNanos >= 0 && probe.deltaNanos >= 0);
        assertEquals(2, countLines("page "));
        String first = sink.lines.get(0);
        assertTrue("the first page carries its change row", first.contains("rows=1"));
        assertTrue("the empty continuation page carries none", sink.lines.get(1).contains("rows=0"));
        assertTrue(first.contains("wm=1"));
        String drain = sink.drainLine();
        assertTrue(drain.contains("pages=2"));
        assertTrue(drain.contains("pending=false"));
        assertTrue(drain.contains("restarts=0"));
    }

    @Test public void pageBudgetDrainCountsPagesAndPendingContinuation() {
        conversations.add(new ConversationWatermark("c0", 900L));
        cursors.put("A:c0", new SyncCursorEntity("A", "c0", 1, 0,
                "2026-11-12T00:00:00Z", null, null, false));
        server = request -> {
            long at = cursor(request.getWatermarks().get(0));
            long next = Math.min(65L, at + 1L);
            String row = message("c0", next).replace("m-c0", "m-c0-" + next)
                    .replace("\"seq\":1,", "\"seq\":" + next + ",");
            return envelope(List.of(delta("c0", next, next < 65L, row)), (int) next);
        };

        engine.sync().onErrorComplete().blockingAwait();

        assertEquals("the drain issues exactly one bounded-batch budget of pages",
                SyncEngine.MAX_DRAIN_PAGES * (SyncEngine.MAX_BOUNDED_RETRIES + 1),
                engine.drainProbe.pages);
        assertTrue("work remains on the server when the page bound cuts the drain",
                engine.hasPendingContinuation());
        assertEquals(engine.drainProbe.pages, countLines("page "));
        String drain = sink.drainLine();
        assertTrue(drain.contains("pages=60"));
        assertTrue(drain.contains("pending=true"));
        assertNull(engine.drainProbe.drainErrorClass);
    }

    @Test public void guardMismatchMidDrainCountsRestartAndClosesTheWindow() {
        seed();
        server = request -> {
            // A competing reset lands between the drain's snapshot and its commit.
            SyncPageStore resetter = new SyncPageStore(db, refresher, tokens, moshi);
            SyncResponse reset = parseResponse(envelope(List.of(page("c0", 0, 0, false, false, 2, "")
                    .replace("\"reset\":false", "\"reset\":true")), 1));
            resetter.apply("A", refresher.sessionEpoch(), Map.of("c0", cursors.get("A:c0")), reset);
            return envelope(List.of(delta("c0", 2, false, message("c0", 2))), 2);
        };

        engine.sync().onErrorComplete().blockingAwait(); // the abort is swallowed as completion

        SyncDrainProbe probe = engine.drainProbe;
        assertEquals("one guard mismatch is one counted restart", 1, probe.restarts);
        assertFalse("the drain window closes even when the page aborted", probe.drainActive);
        assertEquals("the aborted page never completed", 0, probe.pages);
        assertEquals(CancellationException.class.getName(), probe.drainErrorClass);
        String drain = sink.drainLine();
        assertTrue(drain.contains("restarts=1"));
        assertTrue(drain.contains("error=java.util.concurrent.CancellationException"));
        assertTrue("the failed guard's span still lands in the drain totals",
                drain.contains("guardMs="));
    }

    @Test public void drainWindowFlagIsOpenOnlyAcrossTheConsumerDrain() {
        seed();
        AtomicBoolean sawOpenWindow = new AtomicBoolean();
        server = request -> {
            sawOpenWindow.set(engine.drainProbe.drainActive);
            return envelope(List.of(delta("c0", 2, false, message("c0", 2))), 2);
        };

        engine.sync().onErrorComplete().blockingAwait();

        assertTrue("the flag is set across the drain", sawOpenWindow.get());
        assertFalse(engine.drainProbe.drainActive);
        assertEquals(1, engine.drainProbe.pages);
    }

    @Test public void observerEmissionsCountWhileDrainIsActiveAndFallToIdleAfterwards() {
        SyncDrainProbe probe = engine.drainProbe;
        probe.onInboxEmission();     // dormant: no drain ever armed the counters
        probe.onMessagesEmission();
        assertEquals(0, probe.inboxIdle.get());
        assertEquals(0, probe.messagesIdle.get());

        probe.beginDrain();         // the recording sink arms counting and opens the window
        probe.onInboxEmission();
        probe.onMessagesEmission();
        probe.onMessagesEmission();
        assertEquals(1, probe.inboxDuringDrain.get());
        assertEquals(2, probe.messagesDuringDrain.get());

        probe.endDrain(false, 1_000_000L);
        probe.onInboxEmission();    // between drains the same counters tick idle
        assertEquals(1, probe.inboxDuringDrain.get());
        assertEquals(1, probe.inboxIdle.get());
        assertEquals(0, probe.messagesIdle.get());
        String drain = sink.drainLine();
        assertTrue(drain.contains("inbox=1"));
        assertTrue(drain.contains("messages=2"));

        probe.beginDrain();         // per-drain observer counters reset on the next drain
        assertEquals(0, probe.inboxDuringDrain.get());
        assertEquals(0, probe.messagesDuringDrain.get());
    }

    @Test public void repositoryFlowsRouteTheirEmissionsIntoTheDrainProbe() {
        SyncDrainProbe probe = engine.drainProbe;
        probe.beginDrain();         // arms observer counting and opens the drain window

        OutboxDao outbox = mock(OutboxDao.class);
        when(messages.messagesForConversation("c0")).thenReturn(Flowable.just(List.of()));
        when(outbox.pendingOrdered(anyString())).thenReturn(Flowable.just(List.of()));
        when(reactions.reactionsForConversation("c0")).thenReturn(Flowable.just(List.of()));
        when(tokens.observeLocallyHiddenMessageIds()).thenReturn(Flowable.just(Set.of()));
        RealtimeSession session = mock(RealtimeSession.class);
        when(session.getEvents()).thenReturn(PublishProcessor.<ChatEvent>create());
        when(session.getResyncRequests()).thenReturn(PublishProcessor.create());
        MessageRepository messageRepository = new MessageRepository(api, messages, inbox, outbox,
                reactions, mock(PinDao.class), session, tokens,
                new SessionEvents(), engine, moshi, Schedulers.trampoline(),
                new SessionOperationTracker());
        TestSubscriber<List<ChatMessage>> rendered =
                messageRepository.messagesFlow("c0").test();
        assertTrue("messagesFlow emits through the counting hook",
                rendered.values().size() >= 1);
        rendered.cancel();

        ConversationDao dao = mock(ConversationDao.class);
        when(dao.inboxFlow()).thenReturn(Flowable.just(List.of(conversation("c0"))));
        PresenceRepository presence = mock(PresenceRepository.class);
        when(presence.getPresences()).thenReturn(Flowable.just(Map.of()));
        RealtimeSession conversationSession = mock(RealtimeSession.class);
        when(conversationSession.getEvents()).thenReturn(PublishProcessor.<ChatEvent>create());
        when(conversationSession.getResyncRequests()).thenReturn(PublishProcessor.create());
        ConversationRepository conversationRepository = new ConversationRepository(api,
                mock(GroupApi.class), mock(ConversationSettingsApi.class), dao, presence,
                conversationSession, tokens, moshi, mock(Context.class), db,
                new SessionOperationTracker(), new InboxReadWatermarkCache(), messageRepository);
        TestSubscriber<List<ConversationSummary>> inboxSubscriber =
                conversationRepository.inboxFlow().test();
        assertTrue("inboxFlow emits through the counting hook",
                inboxSubscriber.values().size() >= 1);
        inboxSubscriber.cancel();

        assertTrue("messagesFlow emissions land in the drain counter",
                probe.messagesDuringDrain.get() >= 1);
        assertTrue("inboxFlow emissions land in the drain counter",
                probe.inboxDuringDrain.get() >= 1);
        probe.endDrain(false, 0L);
        assertFalse(probe.drainActive);
    }

    private void restart() {
        engine = new SyncEngine(db, api, messages, inbox, reactions, tokens, moshi);
        sink = new RecordingSink();
        engine.drainProbe.sink = sink;
    }

    private long countLines(String prefix) {
        return sink.lines.stream().filter(line -> line.startsWith(prefix)).count();
    }

    private void seed() {
        conversations.add(new ConversationWatermark("c0", 900L));
        cursors.put("A:c0", new SyncCursorEntity("A", "c0", 1, 0,
                "2026-11-12T00:00:00Z", null, null, false));
    }

    private SyncResponse parseResponse(String json) {
        try { return moshi.adapter(SyncResponse.class).fromJson(json); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    private long cursor(SyncRequest.Watermark watermark) {
        // Read the wire, so this fixture matches the engine test's helper shape.
        try {
            Map<?, ?> json = moshi.adapter(Map.class).fromJson(
                    moshi.adapter(SyncRequest.Watermark.class).toJson(watermark));
            Object value = json.get("changeCursor");
            return value instanceof Number ? ((Number) value).longValue() : 0L;
        } catch (Exception error) { throw new AssertionError(error); }
    }

    private static ConversationEntity conversation(String id) {
        return new ConversationEntity(id, "DIRECT", null, 1L, "before", "peer", 0L,
                0, "peer", null, null, 0L, null, "peer", false,
                0L, false, null, false, false, false, null);
    }

    private static String message(String conversation, long version) {
        return "{\"id\":\"m-" + conversation + "\",\"conversationId\":\"" + conversation
                + "\",\"seq\":1,\"senderId\":\"peer\",\"type\":\"TEXT\",\"content\":\"v" + version
                + "\",\"changeVersion\":" + version + ",\"generation\":1,\"reactions\":[],"
                + "\"createdAt\":\"2026-11-12T00:00:00Z\"}";
    }

    private static String delta(String conversation, long cursor, boolean more, String changes) {
        return "{\"conversationId\":\"" + conversation + "\",\"changes\":[" + changes
                + "],\"changeCursor\":" + cursor + ",\"changeUpperBound\":" + (more ? 65 : cursor)
                + ",\"generation\":1,\"changeHasMore\":" + more + ",\"hasMore\":" + more
                + ",\"reset\":false}";
    }

    private static String page(String conversation, long cursor, long upper, boolean changeMore,
            boolean more, long generation, String changes) {
        return "{\"conversationId\":\"" + conversation + "\",\"changes\":[" + changes
                + "],\"changeCursor\":" + cursor + ",\"changeUpperBound\":" + upper
                + ",\"generation\":" + generation + ",\"changeHasMore\":" + changeMore
                + ",\"hasMore\":" + more + ",\"reset\":false}";
    }

    private static String envelope(List<String> deltas, int time) {
        return "{\"syncVersion\":2,\"deltas\":[" + String.join(",", deltas)
                + "],\"serverTime\":\"2026-11-12T00:" + String.format(Locale.ROOT, "%02d:%02d", time / 60, time % 60) + "Z\"}";
    }

    static String jwt(String userId) {
        return "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + userId + "\"}").getBytes(StandardCharsets.UTF_8)) + ".fixture";
    }

    @SuppressWarnings("unchecked") private static Call<SyncResponse> response(SyncResponse body)
            throws Exception {
        Call<SyncResponse> call = mock(Call.class);
        when(call.clone()).thenReturn(call);
        when(call.execute()).thenReturn(Response.success(body));
        return call;
    }
}
