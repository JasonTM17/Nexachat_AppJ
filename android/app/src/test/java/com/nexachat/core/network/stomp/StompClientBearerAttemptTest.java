package com.nexachat.core.network.stomp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** CONNECT bearer selection and attempt-generation suppression. */
public final class StompClientBearerAttemptTest {

    @Before
    public void trampolineIo() {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
    }

    @After
    public void resetPlugins() {
        RxJavaPlugins.reset();
    }

    @Test
    public void explicitBearerIsObservedWithoutReadingStore() {
        TokenStore store = mock(TokenStore.class);
        when(store.readTokens()).thenReturn(Maybe.error(new AssertionError("store read")));
        StompClient client = new StompClient(new OkHttpClient(), store);
        AtomicReference<String> observed = new AtomicReference<>();
        client.setConnectBearerObserverForTests(observed::set);
        client.connect("attempt-bearer")
                .test()
                .assertValueCount(1)
                .assertValueAt(0, StompClient.State.Connecting.INSTANCE);
        assertEquals("attempt-bearer", observed.get());
    }

    @Test
    public void supersededAttemptDoesNotPublishInboundFrames() {
        TokenStore store = mock(TokenStore.class);
        when(store.readTokens()).thenReturn(Maybe.just(new Tokens("first", Long.MAX_VALUE, "r")));
        StompClient client = new StompClient(new OkHttpClient(), store);
        Map<Long, WebSocketListener> listeners = new ConcurrentHashMap<>();
        client.setTestListenerCapture((listener, attemptId) -> listeners.put(attemptId, listener));
        client.connect("first").test().assertValueAt(0, StompClient.State.Connecting.INSTANCE);
        WebSocketListener stale = listeners.get(1L);
        client.connect("second").test().assertValueAt(0, StompClient.State.Connecting.INSTANCE);

        TestSubscriber<StompFrame> inbound = client.getFrames().test();
        String staleMessage = StompCodec.encode(new StompFrame(StompCommand.MESSAGE));
        WebSocket socket = mock(WebSocket.class);
        stale.onMessage(socket, staleMessage);
        inbound.assertNoValues();

        String staleError = StompCodec.encode(
                new StompFrame(StompCommand.ERROR, Map.of(), "stale"));
        stale.onMessage(socket, staleError);
        inbound.assertNoValues();
    }

    @Test
    public void supersededAttemptCloseDoesNotPublishTerminalState() {
        TokenStore store = mock(TokenStore.class);
        when(store.readTokens()).thenReturn(Maybe.just(new Tokens("first", Long.MAX_VALUE, "r")));
        StompClient client = new StompClient(new OkHttpClient(), store);
        Map<Long, WebSocketListener> listeners = new ConcurrentHashMap<>();
        client.setTestListenerCapture((listener, attemptId) -> listeners.put(attemptId, listener));
        io.reactivex.rxjava3.subscribers.TestSubscriber<StompClient.State> firstAttempt =
                client.connect("first").test();
        WebSocketListener stale = listeners.get(1L);
        client.connect("second").test();

        WebSocket socket = mock(WebSocket.class);
        stale.onClosed(socket, 1000, "stale");
        firstAttempt.assertValueAt(0, StompClient.State.Connecting.INSTANCE);
        firstAttempt.assertNoErrors();
        assertFalse(client.isOpen());
    }

    @Test
    public void disposedAttemptIgnoresLateListenerCallbacksWithoutReplacementAttempt() {
        TokenStore store = mock(TokenStore.class);
        StompClient client = new StompClient(new OkHttpClient(), store);
        Map<Long, WebSocketListener> listeners = new ConcurrentHashMap<>();
        Map<Long, WebSocket> sockets = new ConcurrentHashMap<>();
        List<String> sentFrames = new ArrayList<>();
        client.setTestListenerCapture((listener, attemptId) -> listeners.put(attemptId, listener));
        client.setTestWebSocketFactoryForTests((listener, attemptId) -> {
            WebSocket socket = mock(WebSocket.class);
            when(socket.send(anyString())).thenAnswer(invocation -> {
                sentFrames.add(invocation.getArgument(0));
                return true;
            });
            sockets.put(attemptId, socket);
            return socket;
        });
        TestSubscriber<StompClient.State> attempt = client.connect("account-a").test();
        WebSocketListener listener = listeners.get(1L);
        WebSocket socket = sockets.get(1L);
        attempt.cancel();
        sentFrames.clear();
        clearInvocations(socket);

        TestSubscriber<StompFrame> inbound = client.getFrames().test();
        listener.onOpen(socket, mock(Response.class));
        listener.onMessage(socket, StompCodec.encode(new StompFrame(StompCommand.CONNECTED)));
        boolean openedAfterDispose = client.isOpen();
        listener.onMessage(socket, StompCodec.encode(new StompFrame(
                StompCommand.MESSAGE, Map.of("destination", "/user/queue/inbox"), "{}")));
        listener.onMessage(socket, StompCodec.encode(
                new StompFrame(StompCommand.ERROR, Map.of(), "late error")));
        listener.onClosing(socket, 1000, "late closing");
        listener.onClosed(socket, 1000, "late closed");
        listener.onFailure(socket, new IOException("late failure"), null);

        inbound.assertNoValues();
        assertFalse("late CONNECTED must not change open state", openedAfterDispose);
        assertFalse(client.isOpen());
        attempt.assertValueCount(1);
        assertTrue("no frame, including CONNECT, may be sent after disposal", sentFrames.isEmpty());
        verify(socket, never()).send(anyString());
        verify(socket, never()).close(anyInt(), anyString());
    }

    @Test
    public void changedSessionEpochRejectsListenerBeforeAttemptDisposal() {
        TokenStore store = mock(TokenStore.class);
        TokenRefresher refresher = new TokenRefresher(
                store, mock(SessionEvents.class), () -> null);
        refresher.onSessionEstablished(1L);

        StompClient client = new StompClient(new OkHttpClient(), store);
        Map<Long, WebSocketListener> listeners = new ConcurrentHashMap<>();
        Map<Long, WebSocket> sockets = new ConcurrentHashMap<>();
        List<String> sentFrames = new ArrayList<>();
        client.setTestListenerCapture((listener, attemptId) -> listeners.put(attemptId, listener));
        client.setTestWebSocketFactoryForTests((listener, attemptId) -> {
            WebSocket socket = mock(WebSocket.class);
            when(socket.send(anyString())).thenAnswer(invocation -> {
                sentFrames.add(invocation.getArgument(0));
                return true;
            });
            sockets.put(attemptId, socket);
            return socket;
        });

        TestSubscriber<StompClient.State> attempt = client.connect(
                "account-a", action -> refresher.runIfSessionCurrent(1L, action)).test();
        TestSubscriber<StompFrame> inbound = client.getFrames().test();
        refresher.onSessionEstablished(2L);

        WebSocket socket = sockets.get(1L);
        WebSocketListener listener = listeners.get(1L);
        listener.onOpen(socket, mock(Response.class));
        listener.onMessage(socket, StompCodec.encode(new StompFrame(StompCommand.CONNECTED)));
        listener.onMessage(socket, StompCodec.encode(new StompFrame(
                StompCommand.MESSAGE, Map.of("destination", "/user/queue/inbox"), "late")));

        inbound.assertNoValues();
        assertFalse("a prior epoch's CONNECTED callback must not open the socket", client.isOpen());
        assertTrue("a prior epoch's onOpen callback must not send CONNECT", sentFrames.isEmpty());
        attempt.assertValueCount(1);
        attempt.cancel();
    }

    @Test
    public void sessionEpochAdvanceWaitsForInFlightSocketCallback() throws Exception {
        TokenStore store = mock(TokenStore.class);
        TokenRefresher refresher = new TokenRefresher(
                store, mock(SessionEvents.class), () -> null);
        refresher.onSessionEstablished(1L);

        StompClient client = new StompClient(new OkHttpClient(), store);
        Map<Long, WebSocketListener> listeners = new ConcurrentHashMap<>();
        Map<Long, WebSocket> sockets = new ConcurrentHashMap<>();
        List<String> sentFrames = new ArrayList<>();
        CountDownLatch connectSendStarted = new CountDownLatch(1);
        CountDownLatch releaseConnectSend = new CountDownLatch(1);
        client.setTestListenerCapture((listener, attemptId) -> listeners.put(attemptId, listener));
        client.setTestWebSocketFactoryForTests((listener, attemptId) -> {
            WebSocket socket = mock(WebSocket.class);
            when(socket.send(anyString())).thenAnswer(invocation -> {
                sentFrames.add(invocation.getArgument(0));
                connectSendStarted.countDown();
                if (!releaseConnectSend.await(5L, TimeUnit.SECONDS)) {
                    throw new AssertionError("test did not release the CONNECT send");
                }
                return true;
            });
            sockets.put(attemptId, socket);
            return socket;
        });

        TestSubscriber<StompClient.State> attempt = client.connect(
                "account-a", action -> refresher.runIfSessionCurrent(1L, action)).test();
        TestSubscriber<StompFrame> inbound = client.getFrames().test();
        WebSocket socket = sockets.get(1L);
        WebSocketListener listener = listeners.get(1L);
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        Thread callback = new Thread(() -> {
            try {
                listener.onOpen(socket, mock(Response.class));
            } catch (Throwable failure) {
                callbackFailure.set(failure);
            }
        });
        callback.start();
        assertTrue(connectSendStarted.await(5L, TimeUnit.SECONDS));

        CountDownLatch epochAdvanceStarted = new CountDownLatch(1);
        CountDownLatch epochAdvanceFinished = new CountDownLatch(1);
        Thread epochAdvance = new Thread(() -> {
            epochAdvanceStarted.countDown();
            refresher.onSessionEstablished(2L);
            epochAdvanceFinished.countDown();
        });
        epochAdvance.start();
        assertTrue(epochAdvanceStarted.await(5L, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
        while (epochAdvance.isAlive() && epochAdvance.getState() != Thread.State.BLOCKED
                && System.nanoTime() < deadline) {
            Thread.yield();
        }
        boolean epochAdvanceBlocked = epochAdvance.getState() == Thread.State.BLOCKED;
        boolean oldEpochCurrentDuringCallback = refresher.isSessionCurrent(1L);

        releaseConnectSend.countDown();
        callback.join(TimeUnit.SECONDS.toMillis(5L));
        epochAdvance.join(TimeUnit.SECONDS.toMillis(5L));
        assertFalse("socket callback should finish", callback.isAlive());
        assertFalse("epoch advance should finish", epochAdvance.isAlive());
        assertTrue("epoch advancement must wait for the guarded callback", epochAdvanceBlocked);
        assertTrue(oldEpochCurrentDuringCallback);
        assertNull(callbackFailure.get());
        assertEquals(0L, epochAdvanceFinished.getCount());
        assertFalse(refresher.isSessionCurrent(1L));

        listener.onOpen(socket, mock(Response.class));
        listener.onMessage(socket, StompCodec.encode(new StompFrame(
                StompCommand.MESSAGE, Map.of("destination", "/user/queue/inbox"), "late")));
        assertEquals("the only send happened before the epoch changed", 1, sentFrames.size());
        inbound.assertNoValues();
        assertFalse(client.isOpen());
        attempt.cancel();
    }

    @Test
    public void nullBearerFallsBackToTokenStore() {
        TokenStore store = mock(TokenStore.class);
        when(store.readTokens()).thenReturn(Maybe.just(new Tokens("from-store", Long.MAX_VALUE, "r")));
        StompClient client = new StompClient(new OkHttpClient(), store);
        AtomicReference<String> observed = new AtomicReference<>();
        client.setConnectBearerObserverForTests(observed::set);
        client.connect().test().awaitCount(1);
        assertEquals("from-store", observed.get());
    }
}
