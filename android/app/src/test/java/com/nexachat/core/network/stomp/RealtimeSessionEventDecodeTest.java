package com.nexachat.core.network.stomp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.network.AppForegroundState;
import com.nexachat.core.network.NetworkMonitor;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import io.reactivex.rxjava3.processors.FlowableProcessor;
import io.reactivex.rxjava3.processors.PublishProcessor;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import org.junit.Test;

public final class RealtimeSessionEventDecodeTest {

    private final FlowableProcessor<StompFrame> frames = PublishProcessor.<StompFrame>create().toSerialized();

    @Test
    public void lifecycleRevisionsSurviveTheActualStompDecoder() {
        RealtimeSession session = session(true, null);
        TestSubscriber<ChatEvent> observer = session.getEvents().test();
        frames.onNext(message("{\"eventType\":\"MESSAGE_UPDATED\",\"conversationId\":\"c-1\","
                + "\"payload\":{\"id\":\"m-1\",\"seq\":1,\"content\":\"new\","
                + "\"changeVersion\":9007199254740993,\"generation\":2}}"));
        frames.onNext(message("{\"eventType\":\"MESSAGE_DELETED\",\"conversationId\":\"c-1\","
                + "\"payload\":{\"messageId\":\"m-1\",\"deletedAt\":\"2026-11-12T00:00:00Z\","
                + "\"changeVersion\":9007199254740994,\"generation\":2}}"));
        observer.awaitCount(2).assertNoErrors();
        ChatEvent.MessageUpdated updated = (ChatEvent.MessageUpdated) observer.values().get(0);
        ChatEvent.MessageDeleted deleted = (ChatEvent.MessageDeleted) observer.values().get(1);
        assertEquals(Long.valueOf(9_007_199_254_740_993L), updated.getMessage().getChangeVersion());
        assertEquals(Long.valueOf(2), updated.getMessage().getGeneration());
        assertEquals(Long.valueOf(9_007_199_254_740_994L), deleted.getChangeVersion());
        assertEquals(Long.valueOf(2), deleted.getGeneration());
    }

    @Test
    public void messageCreatedDecodesFullPayload() {
        RealtimeSession session = session(true, null);
        TestSubscriber<ChatEvent> observer = session.getEvents().test();
        frames.onNext(message("{\"eventType\":\"MESSAGE_CREATED\",\"conversationId\":\"c-1\","
                + "\"serverTs\":\"2026-11-03T09:12:04Z\",\"payload\":{\"id\":\"m-1\","
                + "\"seq\":1,\"conversationId\":\"c-1\",\"senderId\":\"u-1\","
                + "\"type\":\"TEXT\",\"content\":\"hello\",\"createdAt\":\"2026-11-03T09:12:04Z\"}}"));
        ChatEvent.MessageCreated event = (ChatEvent.MessageCreated) observer.awaitCount(1).values().get(0);
        assertEquals("c-1", event.getConversationId());
        assertEquals("m-1", event.getMessage().getId());
        assertEquals(Long.valueOf(1L), event.getMessage().getSeq());
        assertEquals("hello", event.getMessage().getContent());
    }

    @Test
    public void conversationUpdatedDecodesChangeAndMemberSnapshot() {
        RealtimeSession session = session(true, null);
        TestSubscriber<ChatEvent> observer = session.getEvents().test();
        frames.onNext(message("{\"eventType\":\"CONVERSATION_UPDATED\",\"conversationId\":\"c-1\","
                + "\"payload\":{\"conversationId\":\"c-1\",\"change\":\"MEMBER_REMOVED\","
                + "\"members\":[{\"userId\":\"u-2\"}]}}"));
        ChatEvent.ConversationUpdated event =
                (ChatEvent.ConversationUpdated) observer.awaitCount(1).values().get(0);
        assertEquals("c-1", event.getConversationId());
        assertEquals("MEMBER_REMOVED", event.getChange());
        assertEquals(java.util.List.of("u-2"), event.getMemberIds());
        assertTrue(event.isRemovalEvent());
        assertTrue(event.isSelfRemoval("me-1"));
        assertFalse(event.isSelfRemoval("u-2"));
    }

    @Test
    public void everyOpenResubscribesPresenceQueue() {
        TokenStore store = tokenStore(true);
        StompClient stomp = stomp();
        when(stomp.connect(any(), any())).thenReturn(Flowable.just(StompClient.State.Open.INSTANCE));
        session(store, stomp, null, true);
        verify(stomp, times(1)).subscribe(RealtimeSession.USER_PRESENCE_DESTINATION);
    }

    @Test
    public void signingInAgainAfterLogoutRestoresTheUserQueues() {
        BehaviorProcessor<Boolean> hasRefresh = BehaviorProcessor.createDefault(true);
        BehaviorProcessor<Boolean> admitted = BehaviorProcessor.createDefault(true);
        TokenStore store = mock(TokenStore.class);
        when(store.getHasValidRefresh()).thenReturn(hasRefresh);
        when(store.readTokens()).thenReturn(Maybe.just(new Tokens("access", Long.MAX_VALUE, "refresh")));
        StompClient stomp = stomp();
        PublishProcessor<StompClient.State> socket = PublishProcessor.create();
        when(stomp.connect(any(), any())).thenReturn(socket);
        NetworkMonitor monitor = mock(NetworkMonitor.class);
        when(monitor.getIsOnline()).thenReturn(Flowable.just(false));
        AppForegroundState foreground = () -> Flowable.just(false);
        TokenRefresher refresher = mock(TokenRefresher.class);
        when(refresher.sessionAdmission()).thenReturn(admitted);
        when(refresher.sessionEpochChanges()).thenReturn(Flowable.never());
        TestSessionSignals.stub(refresher);
        when(refresher.sessionEpoch()).thenReturn(1L);
        when(refresher.isSessionCurrent(1L)).thenAnswer(ignored -> admitted.getValue());
        when(refresher.runIfSessionCurrent(anyLong(), any())).thenAnswer(invocation -> {
            if (invocation.getArgument(0, Long.class) != 1L || !admitted.getValue()) return false;
            invocation.<Runnable>getArgument(1).run();
            return true;
        });
        RealtimeSession session = new RealtimeSession(stomp, store, refresher, new Moshi.Builder().build(),
                new ReconnectPolicy(), monitor, foreground);
        socket.onNext(StompClient.State.Open.INSTANCE);
        verify(stomp, times(1)).subscribe(RealtimeSession.USER_INBOX_DESTINATION);
        session.watchConversation("previous-account");
        verify(stomp).subscribe(RealtimeSession.TOPIC_PREFIX + "previous-account");

        hasRefresh.onNext(false);
        admitted.onNext(false);
        clearInvocations(stomp);
        hasRefresh.onNext(true);
        admitted.onNext(true);
        socket.onNext(StompClient.State.Open.INSTANCE);

        verify(stomp).subscribe(RealtimeSession.USER_INBOX_DESTINATION);
        verify(stomp).subscribe(RealtimeSession.USER_ERRORS_DESTINATION);
        verify(stomp).subscribe(RealtimeSession.USER_PRESENCE_DESTINATION);
        verify(stomp, never()).subscribe(RealtimeSession.TOPIC_PREFIX + "previous-account");
    }

    @Test
    public void messageUpdatedDecodesFullPayload() {
        RealtimeSession session = session(true, null);
        TestSubscriber<ChatEvent> observer = session.getEvents().test();
        frames.onNext(message("{\"eventType\":\"MESSAGE_UPDATED\",\"conversationId\":\"c-1\","
                + "\"serverTs\":\"2026-11-03T10:00:00Z\",\"payload\":{\"id\":\"m-1\","
                + "\"seq\":1,\"conversationId\":\"c-1\",\"senderId\":\"u-1\","
                + "\"type\":\"TEXT\",\"content\":\"edited\",\"editedAt\":\"2026-11-03T10:00:00Z\"}}"));
        ChatEvent.MessageUpdated event = (ChatEvent.MessageUpdated) observer.awaitCount(1).values().get(0);
        assertEquals("c-1", event.getConversationId());
        assertEquals("m-1", event.getMessage().getId());
        assertEquals("edited", event.getMessage().getContent());
        assertEquals("2026-11-03T10:00:00Z", event.getMessage().getEditedAt());
    }

    @Test
    public void messageDeletedDecodesIdAndTime() {
        RealtimeSession session = session(true, null);
        TestSubscriber<ChatEvent> observer = session.getEvents().test();
        frames.onNext(message("{\"eventType\":\"MESSAGE_DELETED\",\"conversationId\":\"c-1\","
                + "\"payload\":{\"messageId\":\"m-1\",\"deletedAt\":\"2026-11-03T11:00:00Z\"}}"));
        ChatEvent.MessageDeleted event = (ChatEvent.MessageDeleted) observer.awaitCount(1).values().get(0);
        assertEquals("c-1", event.getConversationId());
        assertEquals("m-1", event.getMessageId());
        assertEquals("2026-11-03T11:00:00Z", event.getDeletedAt());
    }

    @Test
    public void onlineAndOfflineDecodePresenceAndLastSeen() {
        RealtimeSession session = session(true, null);
        TestSubscriber<ChatEvent> observer = session.getEvents().test();
        frames.onNext(message("{\"eventType\":\"USER_ONLINE\",\"serverTs\":\"2026-11-03T11:59:00.000Z\","
                + "\"payload\":{\"userId\":\"u-2\",\"online\":true}}"));
        frames.onNext(message("{\"eventType\":\"USER_OFFLINE\",\"serverTs\":\"2026-11-03T12:00:05.000Z\","
                + "\"payload\":{\"userId\":\"u-2\",\"online\":false,\"lastSeenAt\":\"2026-11-03T12:00:00Z\"}}"));
        observer.awaitCount(2);
        ChatEvent.Presence online = (ChatEvent.Presence) observer.values().get(0);
        ChatEvent.Presence offline = (ChatEvent.Presence) observer.values().get(1);
        assertEquals("u-2", online.getUserId());
        assertTrue(online.getOnline());
        assertEquals("2026-11-03T11:59:00.000Z", online.getServerTs());
        assertFalse(offline.getOnline());
        assertEquals("2026-11-03T12:00:00Z", offline.getLastSeenAt());
        assertEquals("2026-11-03T12:00:05.000Z", offline.getServerTs());
    }

    @Test
    public void errorQueueDecodesBothCodes() {
        RealtimeSession session = session(true, "errors-sub");
        TestSubscriber<ChatEvent> observer = session.getEvents().test();
        frames.onNext(new StompFrame(StompCommand.MESSAGE,
                java.util.Collections.singletonMap("subscription", "errors-sub"),
                "{\"code\":\"RATE_LIMITED\",\"clientMessageId\":\"client-1\"}"));
        frames.onNext(new StompFrame(StompCommand.MESSAGE,
                java.util.Collections.singletonMap("subscription", "errors-sub"),
                "{\"code\":\"INTERNAL_ERROR\"}"));
        observer.awaitCount(2);
        ChatEvent.SendRejected first = (ChatEvent.SendRejected) observer.values().get(0);
        ChatEvent.SendRejected second = (ChatEvent.SendRejected) observer.values().get(1);
        assertEquals("RATE_LIMITED", first.getCode());
        assertEquals("client-1", first.getClientMessageId());
        assertEquals("INTERNAL_ERROR", second.getCode());
        assertEquals(null, second.getClientMessageId());
    }

    @Test
    public void unknownTypesAndMalformedFramesDoNotKillStream() {
        RealtimeSession session = session(true, null);
        TestSubscriber<ChatEvent> observer = session.getEvents().test();
        frames.onNext(message("not json"));
        frames.onNext(message("{\"eventType\":\"FUTURE_EVENT\",\"conversationId\":\"c-1\",\"payload\":{\"x\":1}}"));
        frames.onNext(message("{\"eventType\":\"USER_ONLINE\",\"payload\":{\"userId\":\"u-3\",\"online\":true}}"));
        observer.awaitCount(2);
        assertTrue(observer.values().get(0) instanceof ChatEvent.Unknown);
        assertEquals("FUTURE_EVENT", ((ChatEvent.Unknown) observer.values().get(0)).getEventType());
        ChatEvent.Presence presence = (ChatEvent.Presence) observer.values().get(1);
        assertEquals("u-3", presence.getUserId());
        observer.assertNoErrors();
    }

    private RealtimeSession session(boolean loggedIn, String errorId) {
        return session(tokenStore(loggedIn), stomp(), errorId, loggedIn);
    }

    private RealtimeSession session(TokenStore store, StompClient stomp, String errorId, boolean loggedIn) {
        if (errorId != null) when(stomp.subscriptionIdFor(RealtimeSession.USER_ERRORS_DESTINATION)).thenReturn(errorId);
        NetworkMonitor monitor = mock(NetworkMonitor.class);
        when(monitor.getIsOnline()).thenReturn(Flowable.just(false));
        AppForegroundState foreground = () -> Flowable.just(false);
        TokenRefresher refresher = mock(TokenRefresher.class);
        BehaviorProcessor<Boolean> admitted = io.reactivex.rxjava3.processors.BehaviorProcessor.createDefault(
                loggedIn);
        when(refresher.sessionAdmission()).thenReturn(admitted);
        when(refresher.sessionEpochChanges()).thenReturn(Flowable.never());
        TestSessionSignals.stub(refresher);
        when(refresher.sessionEpoch()).thenReturn(1L);
        when(refresher.isSessionCurrent(1L)).thenAnswer(ignored -> admitted.getValue());
        when(refresher.runIfSessionCurrent(anyLong(), any())).thenAnswer(invocation -> {
            if (invocation.getArgument(0, Long.class) != 1L || !admitted.getValue()) {
                return false;
            }
            invocation.<Runnable>getArgument(1).run();
            return true;
        });
        return new RealtimeSession(stomp, store, refresher, new Moshi.Builder().build(),
                new ReconnectPolicy(), monitor, foreground);
    }

    private StompClient stomp() {
        StompClient stomp = mock(StompClient.class);
        when(stomp.getFrames()).thenReturn(frames.onBackpressureBuffer());
        when(stomp.subscribe(any())).thenReturn(Flowable.empty());
        when(stomp.connect(any(), any())).thenReturn(Flowable.never());
        return stomp;
    }

    private TokenStore tokenStore(boolean loggedIn) {
        TokenStore store = mock(TokenStore.class);
        when(store.getHasValidRefresh()).thenReturn(Flowable.just(loggedIn));
        when(store.readTokens()).thenReturn(loggedIn
                ? Maybe.just(new Tokens("access", Long.MAX_VALUE, "refresh")) : Maybe.empty());
        return store;
    }

    private static StompFrame message(String json) {
        return new StompFrame(StompCommand.MESSAGE,
                java.util.Collections.singletonMap("subscription", "sub-1"), json);
    }
}
