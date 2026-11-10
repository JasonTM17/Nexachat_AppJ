package com.nexachat.core.network.stomp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
