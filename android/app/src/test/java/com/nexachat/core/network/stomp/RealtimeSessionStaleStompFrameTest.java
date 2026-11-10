package com.nexachat.core.network.stomp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nexachat.core.network.AppForegroundState;
import com.nexachat.core.network.NetworkMonitor;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import com.squareup.moshi.Moshi;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.OkHttpClient;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Stale sockets must not reach {@link RealtimeSession} dispatch via {@link StompClient#getFrames()}. */
public final class RealtimeSessionStaleStompFrameTest {

    @Before
    public void trampolineIo() {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
    }

    @After
    public void resetPlugins() {
        RxJavaPlugins.reset();
    }

    @Test
    public void staleSocketMessageNeverReachesRealtimeSessionEvents() {
        TestScheduler scheduler = new TestScheduler();
        TokenStore store = mock(TokenStore.class);
        BehaviorProcessor<Boolean> tokens = BehaviorProcessor.createDefault(true);
        when(store.getHasValidRefresh()).thenReturn(tokens);
        when(store.readTokens()).thenReturn(
                Maybe.just(new Tokens("access", Long.MAX_VALUE, "refresh")));

        TokenRefresher refresher = mock(TokenRefresher.class);
        BehaviorProcessor<Boolean> admission = BehaviorProcessor.createDefault(true);
        when(refresher.sessionAdmission()).thenReturn(admission);
        when(refresher.sessionEpochChanges()).thenReturn(Flowable.never());
        when(refresher.sessionEpoch()).thenReturn(1L);
        when(refresher.isSessionCurrent(anyLong())).thenReturn(true);
        when(refresher.runIfSessionCurrent(anyLong(), any())).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return true;
        });

        StompClient stomp = new StompClient(new OkHttpClient(), store);
        Map<Long, WebSocketListener> listeners = new ConcurrentHashMap<>();
        stomp.setTestListenerCapture((listener, attemptId) -> listeners.put(attemptId, listener));

        NetworkMonitor monitor = mock(NetworkMonitor.class);
        when(monitor.getIsOnline()).thenReturn(Flowable.just(false));
        AppForegroundState foreground = () -> Flowable.just(false);
        RealtimeSession session = new RealtimeSession(stomp, store, refresher, new Moshi.Builder().build(),
                new ReconnectPolicy(() -> 0.0), monitor, foreground, scheduler);
        scheduler.triggerActions();

        TestSubscriber<ChatEvent> events = session.getEvents().test(0L);
        long attemptsBeforeFirst = latestAttemptId(listeners);
        stomp.connect("first").test();
        long firstAttempt = latestAttemptId(listeners);
        assertTrue(firstAttempt > attemptsBeforeFirst);
        WebSocketListener stale = listeners.get(firstAttempt);
        stomp.connect("second").test();

        String body = "{\"eventType\":\"MESSAGE_CREATED\",\"conversationId\":1,\"payload\":{}}";
        StompFrame frame = new StompFrame(
                StompCommand.MESSAGE,
                Map.of("destination", "/user/queue/inbox"),
                body);
        WebSocket socket = mock(WebSocket.class);
        stale.onMessage(socket, StompCodec.encode(frame));
        events.assertNoValues();
    }

    private static long latestAttemptId(Map<Long, WebSocketListener> listeners) {
        return listeners.keySet().stream().mapToLong(Long::longValue).max().orElse(0L);
    }
}
