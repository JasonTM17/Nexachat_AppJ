package com.nexachat.core.network.stomp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
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
import io.reactivex.rxjava3.processors.BehaviorProcessor;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import io.reactivex.rxjava3.subscribers.TestSubscriber;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.junit.Test;

/**
 * Half-open STOMP sockets: the peer stops delivering frames while local writes
 * still succeed. Time is virtual. {@code cancel()} stands in for OkHttp, which
 * fails the listener when the socket is cancelled.
 */
public final class HalfOpenSocketDetectionTest {

    @Test
    public void backgroundSilenceIsSampledToAboutFortySeconds() {
        Armed armed = Armed.open(false);
        armed.scheduler.advanceTimeTo(StompClient.DEAD_AFTER_MS, TimeUnit.MILLISECONDS);
        assertEquals("the 30s limit is not due yet on the heart-beat that lands on it",
                -1L, armed.lostAtMs.get());
        armed.scheduler.advanceTimeTo(
                StompClient.DEAD_AFTER_MS + StompClient.HEARTBEAT_MS, TimeUnit.MILLISECONDS);
        assertEquals(40_000L, armed.lostAtMs.get());
        assertTrue(armed.heartbeatsSent.get() >= 1);
        assertFalse(armed.states.values().isEmpty());
        assertTrue(armed.states.values().get(armed.states.values().size() - 1)
                instanceof StompClient.State.Failed);
    }

    @Test
    public void foregroundHalfOpenLinkIsMarkedLostWithinFifteenSeconds() {
        Armed armed = Armed.open(true);
        armed.scheduler.advanceTimeTo(60_000L, TimeUnit.MILLISECONDS);
        assertEquals(
                "heartbeatsSent=" + armed.heartbeatsSent.get()
                        + " (writes succeeded; detection is inbound silence)",
                StompClient.FOREGROUND_DETECT_BOUND_MS,
                armed.lostAtMs.get());
        assertTrue(armed.states.values().get(armed.states.values().size() - 1)
                instanceof StompClient.State.Failed);
    }

    @Test
    public void foregroundWorstCaseAlignmentStaysInsideTheBound() {
        Armed armed = Armed.open(true);
        armed.scheduler.advanceTimeTo(StompClient.FOREGROUND_LIVENESS_POLL_MS, TimeUnit.MILLISECONDS);
        armed.listener.onMessage(armed.socket, "\n");
        long lastInbound = armed.scheduler.now(TimeUnit.MILLISECONDS);
        armed.scheduler.advanceTimeTo(
                lastInbound + StompClient.FOREGROUND_DEAD_AFTER_MS, TimeUnit.MILLISECONDS);
        assertEquals("a beat that lands on the silence limit must be kept",
                -1L, armed.lostAtMs.get());
        armed.scheduler.advanceTimeTo(
                lastInbound + StompClient.FOREGROUND_DETECT_BOUND_MS, TimeUnit.MILLISECONDS);
        assertEquals(lastInbound + StompClient.FOREGROUND_DETECT_BOUND_MS, armed.lostAtMs.get());
        assertEquals(StompClient.FOREGROUND_DETECT_BOUND_MS, armed.lostAtMs.get() - lastInbound);
    }

    @Test
    public void backgroundingStopsTheForegroundPollAndKeepsTheLegacyDelay() {
        Armed armed = Armed.open(true);
        armed.client.setInForeground(false);
        armed.scheduler.advanceTimeTo(StompClient.DEAD_AFTER_MS, TimeUnit.MILLISECONDS);
        assertEquals(-1L, armed.lostAtMs.get());
        armed.scheduler.advanceTimeTo(
                StompClient.DEAD_AFTER_MS + StompClient.HEARTBEAT_MS, TimeUnit.MILLISECONDS);
        assertEquals(40_000L, armed.lostAtMs.get());
    }

    @Test
    public void jitteryHeartbeatsAreNotDropped() {
        Armed armed = Armed.open(true);
        long[] gapsMs = {9_000L, 14_000L, 6_000L, 13_000L, 11_000L, 8_000L, 14_000L};
        long cursor = 0L;
        for (long gap : gapsMs) {
            cursor += gap;
            armed.scheduler.advanceTimeTo(cursor, TimeUnit.MILLISECONDS);
            assertEquals("gap " + gap + " ms ending at " + cursor, -1L, armed.lostAtMs.get());
            armed.listener.onMessage(armed.socket, "\n");
        }
        armed.scheduler.advanceTimeTo(
                cursor + StompClient.FOREGROUND_DEAD_AFTER_MS, TimeUnit.MILLISECONDS);
        assertEquals(-1L, armed.lostAtMs.get());
        assertTrue(armed.client.isOpen());
    }

    @Test
    public void detectedLossUsesTheExistingReconnectBudgetAndParkedDelay() {
        TestScheduler scheduler = new TestScheduler();
        ReconnectPolicy policy = new ReconnectPolicy(() -> 0.0);
        AtomicInteger connects = new AtomicInteger();
        StompClient stomp = new StompClient(new OkHttpClient(), loggedInStore());
        stomp.setClockForTests(() -> scheduler.now(TimeUnit.MILLISECONDS));
        stomp.setWatchdogSchedulerForTests(scheduler);
        stomp.setTestWebSocketFactoryForTests((listener, attemptId) -> {
            connects.incrementAndGet();
            WebSocket socket = mock(WebSocket.class);
            when(socket.send(anyString())).thenReturn(true);
            AtomicBoolean failed = new AtomicBoolean();
            doAnswer(invocation -> {
                if (failed.compareAndSet(false, true)) {
                    listener.onFailure(socket, new IOException("canceled"), null);
                }
                return null;
            }).when(socket).cancel();
            if (attemptId == 1L) {
                listener.onOpen(socket, handshakeResponse());
                listener.onMessage(socket, StompCodec.encode(new StompFrame(StompCommand.CONNECTED)));
            }
            return socket;
        });
        BehaviorProcessor<Boolean> foreground = BehaviorProcessor.createDefault(true);
        RealtimeSession session = session(stomp, foreground, policy, scheduler);

        assertEquals(1, connects.get());
        assertTrue(session.isCurrentlyOpen());

        long loss1 = StompClient.FOREGROUND_DETECT_BOUND_MS;
        scheduler.advanceTimeTo(loss1 - StompClient.FOREGROUND_LIVENESS_POLL_MS, TimeUnit.MILLISECONDS);
        assertTrue("silence inside the grace must stay open", session.isCurrentlyOpen());
        scheduler.advanceTimeTo(loss1, TimeUnit.MILLISECONDS);
        assertFalse(session.isCurrentlyOpen());
        assertEquals(1, connects.get());

        long start2 = loss1 + policy.delayMillis(1);
        scheduler.advanceTimeTo(start2 - 1L, TimeUnit.MILLISECONDS);
        assertEquals("the fast backoff must not be skipped or replaced by the parked delay",
                1, connects.get());
        scheduler.advanceTimeTo(start2, TimeUnit.MILLISECONDS);
        assertEquals(2, connects.get());

        long start = start2;
        for (int failure = 2; failure <= ReconnectPolicy.MAX_ATTEMPTS; failure++) {
            long loss = start + StompClient.CONNECT_ACK_TIMEOUT_MS;
            scheduler.advanceTimeTo(loss, TimeUnit.MILLISECONDS);
            assertEquals(failure, connects.get());
            assertFalse(session.isCurrentlyOpen());
            if (failure == ReconnectPolicy.MAX_ATTEMPTS) {
                long parked = policy.parkedDelayMillis(1);
                assertEquals(RealtimeSession.PARKED_RETRY_BASE_MS, parked);
                scheduler.advanceTimeTo(loss + policy.delayMillis(1), TimeUnit.MILLISECONDS);
                assertEquals("a fast retry must not be scheduled once the budget is spent",
                        ReconnectPolicy.MAX_ATTEMPTS, connects.get());
                scheduler.advanceTimeTo(loss + parked - 1L, TimeUnit.MILLISECONDS);
                assertEquals(ReconnectPolicy.MAX_ATTEMPTS, connects.get());
                scheduler.advanceTimeTo(loss + parked, TimeUnit.MILLISECONDS);
                assertEquals(ReconnectPolicy.MAX_ATTEMPTS + 1, connects.get());
                break;
            }
            start = loss + policy.delayMillis(failure);
            scheduler.advanceTimeTo(start - 1L, TimeUnit.MILLISECONDS);
            assertEquals(failure, connects.get());
            scheduler.advanceTimeTo(start, TimeUnit.MILLISECONDS);
            assertEquals(failure + 1, connects.get());
        }
    }

    private static RealtimeSession session(
            StompClient stomp,
            Flowable<Boolean> foreground,
            ReconnectPolicy policy,
            TestScheduler scheduler) {
        TokenRefresher refresher = mock(TokenRefresher.class);
        BehaviorProcessor<Boolean> admitted = BehaviorProcessor.createDefault(true);
        when(refresher.sessionAdmission()).thenReturn(admitted);
        when(refresher.sessionEpochChanges()).thenReturn(Flowable.never());
        TestSessionSignals.stub(refresher);
        when(refresher.sessionEpoch()).thenReturn(1L);
        when(refresher.isSessionCurrent(anyLong())).thenAnswer(invocation ->
                Boolean.TRUE.equals(admitted.getValue())
                        && invocation.getArgument(0, Long.class) == 1L);
        when(refresher.runIfSessionCurrent(anyLong(), any())).thenAnswer(invocation -> {
            if (!Boolean.TRUE.equals(admitted.getValue())
                    || invocation.getArgument(0, Long.class) != 1L) {
                return false;
            }
            invocation.<Runnable>getArgument(1).run();
            return true;
        });
        NetworkMonitor monitor = mock(NetworkMonitor.class);
        when(monitor.getIsOnline()).thenReturn(BehaviorProcessor.createDefault(true));
        AppForegroundState foregroundState = () -> foreground;
        return new RealtimeSession(
                stomp,
                loggedInStore(),
                refresher,
                new Moshi.Builder().build(),
                policy,
                monitor,
                foregroundState,
                scheduler);
    }

    private static TokenStore loggedInStore() {
        TokenStore store = mock(TokenStore.class);
        BehaviorProcessor<Boolean> loggedIn = BehaviorProcessor.createDefault(true);
        when(store.getHasValidRefresh()).thenReturn(loggedIn);
        when(store.readTokens()).thenReturn(Maybe.just(new Tokens("access", Long.MAX_VALUE, "refresh")));
        return store;
    }

    private static Response handshakeResponse() {
        return new Response.Builder()
                .request(new Request.Builder().url("https://chat.test/ws").build())
                .protocol(Protocol.HTTP_1_1)
                .code(101)
                .message("Switching Protocols")
                .build();
    }

    private static final class Armed {
        final TestScheduler scheduler = new TestScheduler();
        final StompClient client;
        final WebSocket socket;
        final WebSocketListener listener;
        final AtomicLong lostAtMs = new AtomicLong(-1L);
        final AtomicInteger heartbeatsSent = new AtomicInteger();
        final TestSubscriber<StompClient.State> states;

        private Armed(boolean foreground) {
            client = new StompClient(new OkHttpClient(), mock(TokenStore.class));
            client.setClockForTests(() -> scheduler.now(TimeUnit.MILLISECONDS));
            client.setWatchdogSchedulerForTests(scheduler);
            java.util.concurrent.atomic.AtomicReference<WebSocket> socketRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<WebSocketListener> listenerRef =
                    new java.util.concurrent.atomic.AtomicReference<>();
            client.setTestWebSocketFactoryForTests((createdListener, attemptId) -> {
                WebSocket created = mock(WebSocket.class);
                when(created.send(anyString())).thenAnswer(invocation -> {
                    if ("\n".equals(invocation.getArgument(0))) {
                        heartbeatsSent.incrementAndGet();
                    }
                    return true;
                });
                AtomicBoolean failed = new AtomicBoolean();
                doAnswer(invocation -> {
                    if (failed.compareAndSet(false, true)) {
                        lostAtMs.compareAndSet(-1L, scheduler.now(TimeUnit.MILLISECONDS));
                        createdListener.onFailure(created, new IOException("canceled"), null);
                    }
                    return null;
                }).when(created).cancel();
                socketRef.compareAndSet(null, created);
                listenerRef.compareAndSet(null, createdListener);
                return created;
            });
            client.setInForeground(foreground);
            states = client.connect("access").test();
            socket = socketRef.get();
            listener = listenerRef.get();
            listener.onOpen(socket, handshakeResponse());
            listener.onMessage(socket, StompCodec.encode(new StompFrame(StompCommand.CONNECTED)));
        }

        static Armed open(boolean foreground) {
            return new Armed(foreground);
        }
    }
}
