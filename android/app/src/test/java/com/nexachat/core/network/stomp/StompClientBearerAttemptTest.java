package com.nexachat.core.network.stomp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
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
