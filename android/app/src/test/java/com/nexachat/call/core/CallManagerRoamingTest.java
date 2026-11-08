package com.nexachat.call.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CallManagerRoamingTest {
    private ManualScheduler scheduler;
    private FakeSignaling signaling;
    private FakeMediaEngine media;
    private FakePeerNameResolver peerNames;
    private long nowMs;
    private CallManager manager;

    @Before
    public void setUp() {
        scheduler = new ManualScheduler(() -> nowMs, millis -> nowMs += millis);
        signaling = new FakeSignaling();
        media = new FakeMediaEngine();
        peerNames = new FakePeerNameResolver();
        nowMs = 1_000L;
        Executor direct = Runnable::run;
        manager = new CallManager(
                signaling,
                () -> CallRepository.Result.success(new IceConfig(Collections.emptyList(), 60)),
                scheduler,
                direct,
                () -> nowMs,
                () -> "call-roam-1",
                50L,
                false,
                peerNames);
        manager.attachMediaEngine(media);
        scheduler.runCurrent();
    }

    @After
    public void tearDown() {
        manager.close();
        scheduler.runCurrent();
    }

    private void establishActiveCall() {
        manager.startOutgoing("conversation-1", "peer-1", "Peer");
        scheduler.runCurrent();
        signaling.emit(new CallEvent(CallEvent.Type.CALL_ACCEPTED, "CALL_ACCEPTED", "call-roam-1",
                "conversation-1", null, null, null, null, null, null));
        scheduler.runCurrent();
        media.listener.onConnected();
        scheduler.runCurrent();
        assertEquals(CallState.ACTIVE, manager.currentSessionForTest().getState());
    }

    @Test
    public void signalingDisconnectDuringActiveTransitionsToReconnectingNetwork() {
        establishActiveCall();

        signaling.disconnect();
        scheduler.runCurrent();

        assertEquals(CallState.RECONNECTING_NETWORK, manager.currentSessionForTest().getState());
        assertTrue(CallManager.isCallInProgress(manager.currentSessionForTest().getState()));
    }

    @Test
    public void reconnectWithinGraceTimerRestoresActiveAndRestartsIce() {
        establishActiveCall();

        signaling.disconnect();
        scheduler.runCurrent();
        assertEquals(CallState.RECONNECTING_NETWORK, manager.currentSessionForTest().getState());

        // Emit a local ICE candidate while disconnected — should be buffered
        media.listener.onLocalIce(new CallSignal.IceCandidate("audio", 0, "candidate:buffered"));
        scheduler.runCurrent();

        // Advance 5 seconds (within 15s grace timer)
        scheduler.advanceBy(5_000L);
        assertEquals(CallState.RECONNECTING_NETWORK, manager.currentSessionForTest().getState());

        // Reconnect signaling
        signaling.connect();
        scheduler.runCurrent();

        assertEquals(CallState.ACTIVE, manager.currentSessionForTest().getState());
        assertEquals(1, media.restartIceCount);

        // Verify buffered candidate was flushed
        boolean foundBuffered = false;
        for (CallSignal sig : signaling.sent) {
            if (sig.getType() == CallSignal.Type.ICE
                    && sig.getCandidate() != null
                    && "candidate:buffered".equals(sig.getCandidate().getCandidate())) {
                foundBuffered = true;
                break;
            }
        }
        assertTrue("Buffered ICE candidate should be sent upon reconnect", foundBuffered);
    }

    @Test
    public void reconnectGraceTimerExpiresEndsCall() {
        establishActiveCall();

        signaling.disconnect();
        scheduler.runCurrent();
        assertEquals(CallState.RECONNECTING_NETWORK, manager.currentSessionForTest().getState());

        // Advance past 15 seconds grace period
        scheduler.advanceBy(16_000L);

        assertEquals(CallState.ENDED, manager.currentSessionForTest().getState());
        assertEquals(CallEndReason.FAILED, manager.currentSessionForTest().getEndReason());
    }

    @Test
    public void hydrateIncomingCallTransitionsToIncomingRingingOnInvite() {
        manager.hydrateIncomingCall("call-push-1", "conv-push-1", "peer-push-1", "Push Peer", nowMs);
        scheduler.runCurrent();

        assertEquals(CallState.PENDING_INCOMING, manager.currentSessionForTest().getState());
        assertEquals("call-push-1", manager.currentSessionForTest().getCallId());

        // STOMP INVITE arrives
        signaling.emit(new CallEvent(CallEvent.Type.CALL_INVITE, "CALL_INVITE", "call-push-1",
                "conv-push-1", "peer-push-1", null, null, null, null, null));
        scheduler.runCurrent();

        assertEquals(CallState.INCOMING_RINGING, manager.currentSessionForTest().getState());
    }

    @Test
    public void hydrateIncomingCallGraceTimerExpiresMissesCall() {
        manager.hydrateIncomingCall("call-push-2", "conv-push-2", "peer-push-2", "Push Peer", nowMs);
        scheduler.runCurrent();

        assertEquals(CallState.PENDING_INCOMING, manager.currentSessionForTest().getState());

        // Advance 16 seconds without STOMP INVITE
        scheduler.advanceBy(16_000L);

        assertEquals(CallState.ENDED, manager.currentSessionForTest().getState());
        assertEquals(CallEndReason.MISSED, manager.currentSessionForTest().getEndReason());
    }

    private interface Clock {
        long now();
    }

    private interface TimeAdvancer {
        void advanceBy(long millis);
    }

    private static final class ManualScheduler implements CallManager.Scheduler {
        private final List<Runnable> immediate = new ArrayList<>();
        private final List<Scheduled> scheduled = new ArrayList<>();
        private final Clock clock;
        private final TimeAdvancer timeAdvancer;

        ManualScheduler(Clock clock, TimeAdvancer timeAdvancer) {
            this.clock = clock;
            this.timeAdvancer = timeAdvancer;
        }

        @Override
        public void execute(Runnable task) {
            immediate.add(task);
        }

        @Override
        public CallManager.Cancellable schedule(Runnable task, long delayMs) {
            Scheduled entry = new Scheduled(task, clock.now() + delayMs);
            scheduled.add(entry);
            return () -> entry.cancelled = true;
        }

        void runCurrent() {
            while (!immediate.isEmpty()) {
                List<Runnable> batch = new ArrayList<>(immediate);
                immediate.clear();
                for (Runnable task : batch) task.run();
            }
        }

        void advanceBy(long millis) {
            timeAdvancer.advanceBy(millis);
            for (Scheduled entry : scheduled) {
                if (!entry.cancelled && entry.dueAt <= clock.now()) {
                    entry.cancelled = true;
                    immediate.add(entry.task);
                }
            }
            runCurrent();
        }

        private static final class Scheduled {
            final Runnable task;
            final long dueAt;
            boolean cancelled;

            Scheduled(Runnable task, long dueAt) {
                this.task = task;
                this.dueAt = dueAt;
            }
        }
    }

    private static final class FakeSignaling implements CallSignalingClient {
        CallSignalingClient.Listener listener;
        final List<CallSignal> sent = new ArrayList<>();
        boolean connected = true;

        @Override
        public void start(Listener listener) {
            this.listener = listener;
        }

        @Override
        public void stop() { }

        @Override
        public boolean send(String conversationId, CallSignal signal) {
            sent.add(signal);
            return connected;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        void emit(CallEvent event) {
            listener.onEvent(event);
        }

        void disconnect() {
            connected = false;
            listener.onDisconnected();
        }

        void connect() {
            connected = true;
            listener.onConnected();
        }
    }

    private static final class FakePeerNameResolver implements CallPeerNameResolver {
        @Override
        public String unknownCallerDisplayName() {
            return "Unknown caller";
        }

        @Override
        public void resolve(String conversationId, String peerUserId, Callback callback) {
            callback.onResolved("Peer");
        }
    }

    private static final class FakeMediaEngine implements CallMediaEngine {
        int restartIceCount;
        Listener listener;

        @Override
        public void start(boolean isCaller, IceConfig config, Listener listener) {
            this.listener = listener;
        }

        @Override
        public void restartIce() {
            restartIceCount++;
        }

        @Override
        public void onRemoteSdp(CallSignal.Sdp sdp) { }

        @Override
        public void onRemoteIce(CallSignal.IceCandidate candidate) { }

        @Override
        public void setMuted(boolean muted) { }

        @Override
        public void setSpeaker(boolean speakerOn) { }

        @Override
        public void stop() { }
    }
}
