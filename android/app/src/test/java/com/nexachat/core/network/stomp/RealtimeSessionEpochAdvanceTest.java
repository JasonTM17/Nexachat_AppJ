package com.nexachat.core.network.stomp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import io.reactivex.rxjava3.subjects.MaybeSubject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import org.junit.Test;

/** Session epoch advances without admission closing must not strand attemptActive. */
public final class RealtimeSessionEpochAdvanceTest {

    @Test
    public void establishedSessionWithOpenSocketRetiresOldAccountAndConnectsNewBearer() {
        TestScheduler scheduler = new TestScheduler();
        BehaviorProcessor<Boolean> tokensPresent = BehaviorProcessor.createDefault(true);
        AtomicReference<Tokens> storedTokens = new AtomicReference<>(
                new Tokens("account-a", Long.MAX_VALUE, "refresh-a"));
        TokenStore store = mock(TokenStore.class);
        when(store.getHasValidRefresh()).thenReturn(tokensPresent);
        when(store.readTokens()).thenAnswer(ignored -> Maybe.fromCallable(storedTokens::get));

        TokenRefresher refresher = new TokenRefresher(store, mock(SessionEvents.class), () -> null);
        refresher.onSessionEstablished(1L);

        List<String> connectedBearers = new ArrayList<>();
        Map<Long, WebSocketListener> listeners = new ConcurrentHashMap<>();
        Map<Long, WebSocket> sockets = new ConcurrentHashMap<>();
        StompClient stomp = new StompClient(new OkHttpClient(), store);
        stomp.setConnectBearerObserverForTests(connectedBearers::add);
        stomp.setTestListenerCapture((listener, attemptId) -> listeners.put(attemptId, listener));
        stomp.setTestWebSocketFactoryForTests((listener, attemptId) -> {
            WebSocket socket = mock(WebSocket.class);
            sockets.put(attemptId, socket);
            return socket;
        });

        NetworkMonitor monitor = mock(NetworkMonitor.class);
        when(monitor.getIsOnline()).thenReturn(Flowable.just(false));
        AppForegroundState foreground = () -> Flowable.just(false);
        RealtimeSession session = new RealtimeSession(stomp, store, refresher, new Moshi.Builder().build(),
                new ReconnectPolicy(() -> 0.0), monitor, foreground, scheduler);
        scheduler.triggerActions();

        assertEquals(List.of("account-a"), connectedBearers);
        WebSocket socketA = sockets.get(1L);
        WebSocketListener listenerA = listeners.get(1L);
        listenerA.onOpen(socketA, mock(Response.class));
        listenerA.onMessage(socketA, StompCodec.encode(new StompFrame(StompCommand.CONNECTED)));
        assertTrue(session.isCurrentlyOpen());
        TestSubscriber<ChatEvent> events = session.getEvents().test();

        storedTokens.set(new Tokens("account-b", Long.MAX_VALUE, "refresh-b"));
        refresher.onSessionEstablished(2L);
        scheduler.triggerActions();

        assertEquals("account establishment without a false admission edge must replace the bearer",
                List.of("account-a", "account-b"), connectedBearers);
        WebSocket socketB = sockets.get(2L);
        WebSocketListener listenerB = listeners.get(2L);
        listenerB.onOpen(socketB, mock(Response.class));
        listenerB.onMessage(socketB, StompCodec.encode(new StompFrame(StompCommand.CONNECTED)));
        assertTrue("new account must have an open realtime attempt", session.isCurrentlyOpen());

        String oldAccountEvent = "{\"eventType\":\"USER_TYPING\",\"conversationId\":\"conversation-a\","
                + "\"payload\":{\"userId\":\"user-a\",\"displayName\":\"A\"}}";
        listenerA.onMessage(socketA, StompCodec.encode(new StompFrame(
                StompCommand.MESSAGE, Map.of("destination", "/user/queue/inbox"), oldAccountEvent)));
        listenerA.onClosed(socketA, 1000, "stale account socket");

        events.assertNoValues();
        assertTrue("a late close from account A must not clear account B's open state",
                session.isCurrentlyOpen());
        assertTrue("a late close from account A must not clear the active socket", stomp.isOpen());
        verify(socketA).close(anyInt(), anyString());
        session.dispose();
    }

    @Test
    public void epochAdvanceWithoutAdmissionTransitionStartsNewAttempt() {
        TestScheduler scheduler = new TestScheduler();
        BehaviorProcessor<Boolean> tokens = BehaviorProcessor.createDefault(true);
        BehaviorProcessor<Boolean> admission = BehaviorProcessor.createDefault(true);
        AtomicLong epoch = new AtomicLong(1L);
        TokenRefresher refresher = mock(TokenRefresher.class);
        when(refresher.sessionAdmission()).thenReturn(admission);
        when(refresher.sessionEpochChanges()).thenReturn(Flowable.never());
        when(refresher.sessionEpoch()).thenAnswer(ignored -> epoch.get());
        when(refresher.isSessionCurrent(anyLong())).thenAnswer(invocation -> {
            long expected = invocation.getArgument(0);
            return admission.getValue() && expected == epoch.get();
        });
        when(refresher.runIfSessionCurrent(anyLong(), any())).thenAnswer(invocation -> {
            long expected = invocation.getArgument(0);
            if (!admission.getValue() || expected != epoch.get()) {
                return false;
            }
            invocation.<Runnable>getArgument(1).run();
            return true;
        });

        AtomicInteger connects = new AtomicInteger();
        StompClient stomp = mock(StompClient.class);
        when(stomp.getFrames()).thenReturn(Flowable.empty());
        when(stomp.connect(any(), any())).thenAnswer(ignored -> {
            connects.incrementAndGet();
            return Flowable.never();
        });

        TokenStore store = mock(TokenStore.class);
        when(store.getHasValidRefresh()).thenReturn(tokens);
        when(store.readTokens()).thenReturn(
                Maybe.just(new Tokens("access", Long.MAX_VALUE, "refresh")));

        NetworkMonitor monitor = mock(NetworkMonitor.class);
        when(monitor.getIsOnline()).thenReturn(Flowable.just(false));
        AppForegroundState foreground = () -> Flowable.just(false);
        RealtimeSession session = new RealtimeSession(stomp, store, refresher, new Moshi.Builder().build(),
                new ReconnectPolicy(() -> 0.0), monitor, foreground, scheduler);
        scheduler.triggerActions();
        assertEquals(1, connects.get());

        epoch.set(2L);
        session.ensureConnected();
        scheduler.triggerActions();
        assertEquals("epoch advance must clear the stranded attempt gate", 2, connects.get());
    }

    @Test
    public void connectingStateObserverDoesNotBlockSessionEpochAdvance() throws Exception {
        TestScheduler scheduler = new TestScheduler();
        BehaviorProcessor<Boolean> tokensPresent = BehaviorProcessor.createDefault(true);
        AtomicReference<Tokens> storedTokens = new AtomicReference<>(
                new Tokens("account-a", Long.MAX_VALUE, "refresh-a"));
        AtomicInteger tokenReads = new AtomicInteger();
        MaybeSubject<Tokens> firstRead = MaybeSubject.create();
        TokenStore store = mock(TokenStore.class);
        when(store.getHasValidRefresh()).thenReturn(tokensPresent);
        when(store.readTokens()).thenAnswer(ignored -> tokenReads.getAndIncrement() == 0
                ? firstRead
                : Maybe.fromCallable(storedTokens::get));

        TokenRefresher refresher = new TokenRefresher(store, mock(SessionEvents.class), () -> null);
        refresher.onSessionEstablished(1L);

        StompClient stomp = new StompClient(new OkHttpClient(), store);
        Map<Long, WebSocket> sockets = new ConcurrentHashMap<>();
        stomp.setTestWebSocketFactoryForTests((listener, attemptId) -> {
            WebSocket socket = mock(WebSocket.class);
            sockets.put(attemptId, socket);
            return socket;
        });
        NetworkMonitor monitor = mock(NetworkMonitor.class);
        when(monitor.getIsOnline()).thenReturn(Flowable.just(false));
        RealtimeSession session = new RealtimeSession(stomp, store, refresher,
                new Moshi.Builder().build(), new ReconnectPolicy(() -> 0.0), monitor,
                () -> Flowable.just(false), scheduler,
                io.reactivex.rxjava3.schedulers.Schedulers.io());

        CountDownLatch connectingStateEntered = new CountDownLatch(1);
        CountDownLatch releaseConnectingState = new CountDownLatch(1);
        AtomicBoolean blockFirstConnectingState = new AtomicBoolean(true);
        AtomicReference<Throwable> observerFailure = new AtomicReference<>();
        Disposable stateObserver = session.getState().subscribe(connectionState -> {
            if (connectionState instanceof StompClient.State.Connecting
                    && blockFirstConnectingState.compareAndSet(true, false)) {
                connectingStateEntered.countDown();
                try {
                    if (!releaseConnectingState.await(5L, TimeUnit.SECONDS)) {
                        observerFailure.set(new AssertionError("test did not release state observer"));
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    observerFailure.set(interrupted);
                }
            }
        });

        AtomicReference<Throwable> tokenReadFailure = new AtomicReference<>();
        Thread tokenRead = new Thread(() -> {
            try {
                firstRead.onSuccess(new Tokens("account-a", Long.MAX_VALUE, "refresh-a"));
            } catch (Throwable failure) {
                tokenReadFailure.set(failure);
            }
        });
        tokenRead.start();
        boolean connectingStateObserved = connectingStateEntered.await(5L, TimeUnit.SECONDS);
        if (!connectingStateObserved) {
            releaseConnectingState.countDown();
            tokenRead.join(TimeUnit.SECONDS.toMillis(5L));
        }

        CountDownLatch epochAdvanceStarted = new CountDownLatch(1);
        CountDownLatch epochAdvanceFinished = new CountDownLatch(1);
        storedTokens.set(new Tokens("account-b", Long.MAX_VALUE, "refresh-b"));
        Thread epochAdvance = new Thread(() -> {
            epochAdvanceStarted.countDown();
            refresher.onSessionEstablished(2L);
            epochAdvanceFinished.countDown();
        });
        epochAdvance.start();
        boolean advanceStarted = epochAdvanceStarted.await(5L, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (advanceStarted && epochAdvance.isAlive()
                && epochAdvance.getState() != Thread.State.BLOCKED
                && System.nanoTime() < deadline) {
            Thread.yield();
        }
        boolean epochAdvanceBlockedByPublication = advanceStarted
                && epochAdvance.getState() == Thread.State.BLOCKED;
        boolean oldEpochCurrentDuringPublication = refresher.isSessionCurrent(1L);

        releaseConnectingState.countDown();
        tokenRead.join(TimeUnit.SECONDS.toMillis(5L));
        epochAdvance.join(TimeUnit.SECONDS.toMillis(5L));
        assertFalse("token read publication should finish", tokenRead.isAlive());
        assertFalse("epoch advance should finish", epochAdvance.isAlive());
        assertTrue("Connecting state should reach the observer", connectingStateObserved);
        assertFalse("session epoch advancement must not wait for a state observer",
                epochAdvanceBlockedByPublication);
        assertFalse("epoch should advance before the blocked observer is released",
                oldEpochCurrentDuringPublication);
        assertNull(observerFailure.get());
        assertNull(tokenReadFailure.get());
        assertEquals(0L, epochAdvanceFinished.getCount());
        assertFalse(refresher.isSessionCurrent(1L));

        stateObserver.dispose();
        session.dispose();
    }
}
