package com.nexachat.core.network.stomp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.network.AppForegroundState;
import com.nexachat.core.network.NetworkMonitor;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import io.reactivex.rxjava3.processors.FlowableProcessor;
import io.reactivex.rxjava3.processors.PublishProcessor;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Pins the open-conversation contract: the topic subscription exists while a
 * conversation is open, survives reconnects, and MESSAGE_CREATED frames reach
 * event observers; a parked socket re-arms on foreground.
 */
public final class RealtimeSessionOpenConversationTest {

    @Test
    public void watchConversationSubscribesTopicImmediatelyWhileOpen() {
        PublishProcessor<StompClient.State> socket = PublishProcessor.create();
        StompClient stomp = stompOn(socket);
        RealtimeSession session = openSession(stomp);
        socket.onNext(StompClient.State.Open.INSTANCE);
        assertTrue(session.isCurrentlyOpen());

        session.watchConversation("c-1");

        verify(stomp, times(1)).subscribe(RealtimeSession.TOPIC_PREFIX + "c-1");
    }

    @Test
    public void watchConversationBeforeOpenSubscribesOnOpen() {
        PublishProcessor<StompClient.State> socket = PublishProcessor.create();
        StompClient stomp = stompOn(socket);
        RealtimeSession session = openSession(stomp);

        session.watchConversation("c-1");
        verify(stomp, times(0)).subscribe(RealtimeSession.TOPIC_PREFIX + "c-1");

        socket.onNext(StompClient.State.Open.INSTANCE);

        verify(stomp, times(1)).subscribe(RealtimeSession.TOPIC_PREFIX + "c-1");
    }

    @Test
    public void reconnectResubscribesTheWatchedConversation() {
        TestScheduler scheduler = new TestScheduler();
        PublishProcessor<StompClient.State> socket = PublishProcessor.create();
        StompClient stomp = stompOn(socket);
        RealtimeSession session = session(loggedInStore(Long.MAX_VALUE), stomp,
                BehaviorProcessor.createDefault(false), BehaviorProcessor.createDefault(false),
                mock(TokenRefresher.class), scheduler);
        session.watchConversation("c-1");
        socket.onNext(StompClient.State.Open.INSTANCE);
        verify(stomp, times(1)).subscribe(RealtimeSession.TOPIC_PREFIX + "c-1");

        clearInvocations(stomp);
        socket.onNext(new StompClient.State.Closed(1006, "lost"));
        scheduler.advanceTimeBy(2, TimeUnit.SECONDS);
        socket.onNext(StompClient.State.Open.INSTANCE);

        verify(stomp, times(1)).subscribe(RealtimeSession.TOPIC_PREFIX + "c-1");
        verify(stomp, times(1)).subscribe(RealtimeSession.USER_INBOX_DESTINATION);
    }

    @Test
    public void unwatchConversationStopsResubscriptionOnReconnect() {
        TestScheduler scheduler = new TestScheduler();
        PublishProcessor<StompClient.State> socket = PublishProcessor.create();
        StompClient stomp = stompOn(socket);
        RealtimeSession session = session(loggedInStore(Long.MAX_VALUE), stomp,
                BehaviorProcessor.createDefault(false), BehaviorProcessor.createDefault(false),
                mock(TokenRefresher.class), scheduler);
        session.watchConversation("c-1");
        socket.onNext(StompClient.State.Open.INSTANCE);

        session.unwatchConversation("c-1");
        verify(stomp, times(1)).unsubscribe(RealtimeSession.TOPIC_PREFIX + "c-1");

        clearInvocations(stomp);
        socket.onNext(new StompClient.State.Closed(1006, "lost"));
        scheduler.advanceTimeBy(2, TimeUnit.SECONDS);
        socket.onNext(StompClient.State.Open.INSTANCE);

        verify(stomp, times(0)).subscribe(RealtimeSession.TOPIC_PREFIX + "c-1");
        verify(stomp, times(1)).subscribe(RealtimeSession.USER_INBOX_DESTINATION);
    }

    @Test
    public void parkedSessionRearmsWhenAppForegrounds() {
        TestScheduler scheduler = new TestScheduler();
        AtomicInteger connects = new AtomicInteger();
        RealtimeSession session = session(loggedInStore(Long.MAX_VALUE), failedClient(connects),
                BehaviorProcessor.createDefault(false), BehaviorProcessor.createDefault(false),
                mock(TokenRefresher.class), scheduler);
        scheduler.advanceTimeBy(2, TimeUnit.MINUTES);
        assertEquals(ReconnectPolicy.MAX_ATTEMPTS, connects.get());

        session.onAppForeground();
        scheduler.triggerActions();

        assertTrue("foregrounding must re-arm a socket parked after retry exhaustion",
                connects.get() > ReconnectPolicy.MAX_ATTEMPTS);
    }

    @Test
    public void messageCreatedFrameForOpenConversationReachesEventObservers() {
        FlowableProcessor<StompFrame> frames = PublishProcessor.<StompFrame>create().toSerialized();
        PublishProcessor<StompClient.State> socket = PublishProcessor.create();
        StompClient stomp = stompOn(socket);
        when(stomp.getFrames()).thenReturn(frames.onBackpressureBuffer());
        RealtimeSession session = openSession(stomp);
        TestSubscriber<ChatEvent> observer = session.getEvents().test();
        session.watchConversation("c-1");
        socket.onNext(StompClient.State.Open.INSTANCE);

        frames.onNext(message("{\"eventType\":\"MESSAGE_CREATED\",\"conversationId\":\"c-1\","
                + "\"serverTs\":\"2026-11-03T09:12:04Z\",\"payload\":{\"id\":\"m-live\","
                + "\"seq\":7,\"conversationId\":\"c-1\",\"senderId\":\"u-other\","
                + "\"type\":\"TEXT\",\"content\":\"live row\",\"createdAt\":\"2026-11-03T09:12:04Z\"}}"));

        observer.awaitCount(1).assertNoErrors();
        ChatEvent.MessageCreated event = (ChatEvent.MessageCreated) observer.values().get(0);
        assertEquals("c-1", event.getConversationId());
        assertEquals("m-live", event.getMessage().getId());
        assertEquals(Long.valueOf(7L), event.getMessage().getSeq());
    }

    @Test
    public void messageCreatedFrameAfterReconnectStillReachesEventObservers() {
        TestScheduler scheduler = new TestScheduler();
        FlowableProcessor<StompFrame> frames = PublishProcessor.<StompFrame>create().toSerialized();
        PublishProcessor<StompClient.State> socket = PublishProcessor.create();
        StompClient stomp = stompOn(socket);
        when(stomp.getFrames()).thenReturn(frames.onBackpressureBuffer());
        RealtimeSession session = session(loggedInStore(Long.MAX_VALUE), stomp,
                BehaviorProcessor.createDefault(false), BehaviorProcessor.createDefault(false),
                mock(TokenRefresher.class), scheduler);
        TestSubscriber<ChatEvent> observer = session.getEvents().test();
        session.watchConversation("c-1");
        socket.onNext(StompClient.State.Open.INSTANCE);
        socket.onNext(new StompClient.State.Closed(1006, "lost"));
        scheduler.advanceTimeBy(2, TimeUnit.SECONDS);
        socket.onNext(StompClient.State.Open.INSTANCE);

        frames.onNext(message("{\"eventType\":\"MESSAGE_CREATED\",\"conversationId\":\"c-1\","
                + "\"payload\":{\"id\":\"m-after\",\"seq\":9,\"conversationId\":\"c-1\","
                + "\"senderId\":\"u-other\",\"type\":\"TEXT\",\"content\":\"after reconnect\","
                + "\"createdAt\":\"2026-11-03T09:12:05Z\"}}"));

        observer.awaitCount(1).assertNoErrors();
        ChatEvent.MessageCreated event = (ChatEvent.MessageCreated) observer.values().get(0);
        assertEquals("m-after", event.getMessage().getId());
    }

    private RealtimeSession openSession(StompClient stomp) {
        return session(loggedInStore(Long.MAX_VALUE), stomp,
                BehaviorProcessor.createDefault(false), BehaviorProcessor.createDefault(false),
                mock(TokenRefresher.class), new TestScheduler());
    }

    private static RealtimeSession session(
            TokenStore store,
            StompClient stomp,
            Flowable<Boolean> online,
            Flowable<Boolean> foreground,
            TokenRefresher refresher,
            TestScheduler scheduler) {
        NetworkMonitor monitor = mock(NetworkMonitor.class);
        when(monitor.getIsOnline()).thenReturn(online);
        AppForegroundState foregroundState = () -> foreground;
        stubEpochAdmission(refresher);
        return new RealtimeSession(stomp, store, refresher, new Moshi.Builder().build(),
                new ReconnectPolicy(() -> 0.0), monitor, foregroundState, scheduler);
    }

    private static void stubEpochAdmission(TokenRefresher refresher) {
        BehaviorProcessor<Boolean> admitted = BehaviorProcessor.createDefault(true);
        when(refresher.sessionAdmission()).thenReturn(admitted);
        when(refresher.sessionEpochChanges()).thenReturn(Flowable.never());
        TestSessionSignals.stub(refresher);
        when(refresher.sessionEpoch()).thenReturn(1L);
        when(refresher.isSessionCurrent(anyLong())).thenAnswer(
                invocation -> admitted.getValue() && invocation.getArgument(0, Long.class) == 1L);
        when(refresher.runIfSessionCurrent(anyLong(), any())).thenAnswer(invocation -> {
            if (!admitted.getValue() || invocation.getArgument(0, Long.class) != 1L) {
                return false;
            }
            invocation.<Runnable>getArgument(1).run();
            return true;
        });
    }

    private static StompClient stompOn(PublishProcessor<StompClient.State> socket) {
        StompClient stomp = mock(StompClient.class);
        when(stomp.getFrames()).thenReturn(Flowable.empty());
        when(stomp.subscribe(anyString())).thenReturn(Flowable.empty());
        when(stomp.connect(anyString(), any())).thenReturn(socket);
        return stomp;
    }

    private static StompClient failedClient(AtomicInteger count) {
        StompClient stomp = mock(StompClient.class);
        when(stomp.getFrames()).thenReturn(Flowable.empty());
        when(stomp.connect(anyString(), any())).thenAnswer(ignored -> {
            count.incrementAndGet();
            return Flowable.just(new StompClient.State.Failed("offline"));
        });
        return stomp;
    }

    private static TokenStore loggedInStore(long expiresAt) {
        TokenStore store = mock(TokenStore.class);
        BehaviorProcessor<Boolean> loggedIn = BehaviorProcessor.createDefault(true);
        when(store.getHasValidRefresh()).thenReturn(loggedIn);
        when(store.readTokens()).thenAnswer(ignored -> loggedIn.getValue()
                ? Maybe.just(new Tokens("access", expiresAt, "refresh"))
                : Maybe.empty());
        when(store.clear()).thenAnswer(ignored -> Completable.fromAction(() -> loggedIn.onNext(false)));
        return store;
    }

    private static StompFrame message(String json) {
        return new StompFrame(StompCommand.MESSAGE,
                Collections.singletonMap("subscription", "sub-1"), json);
    }
}
