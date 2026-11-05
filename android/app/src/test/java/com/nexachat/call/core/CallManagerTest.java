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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class CallManagerTest {
    private ManualScheduler scheduler;
    private FakeSignaling signaling;
    private FakeMediaEngine media;
    private long nowMs;
    private CallManager manager;

    @Before
    public void setUp() {
        scheduler = new ManualScheduler(() -> nowMs, millis -> nowMs += millis);
        signaling = new FakeSignaling();
        media = new FakeMediaEngine();
        nowMs = 1_000L;
        Executor direct = Runnable::run;
        manager = new CallManager(
                signaling,
                () -> CallRepository.Result.success(new IceConfig(Collections.emptyList(), 60)),
                scheduler,
                direct,
                () -> nowMs,
                () -> "call-1",
                50L,
                false);
        manager.attachMediaEngine(media);
        scheduler.runCurrent();
    }

    @After
    public void tearDown() {
        manager.close();
        scheduler.runCurrent();
    }

    @Test
    public void outgoingCallMovesThroughRingingConnectingActiveAndEnded() {
        manager.startOutgoing("conversation-1", "peer-1", "Peer");
        scheduler.runCurrent();
        assertEquals(CallState.OUTGOING_RINGING, session().getState());
        assertEquals(CallSignal.Type.INVITE, signaling.lastSignal().getType());

        signaling.emit(event(CallEvent.Type.CALL_ACCEPTED, "call-1", null));
        scheduler.runCurrent();
        assertEquals(CallState.CONNECTING, session().getState());
        assertEquals(1, media.startCount);
        assertTrue(media.lastCaller);
        assertTrue(media.remoteSignals.isEmpty());

        media.listener.onConnected();
        scheduler.runCurrent();
        assertEquals(CallState.ACTIVE, session().getState());
        assertEquals(Long.valueOf(nowMs), session().getAnsweredAtMs());

        manager.hangUp();
        scheduler.runCurrent();
        assertEquals(CallState.ENDED, session().getState());
        assertEquals(CallEndReason.COMPLETED, session().getEndReason());
        assertEquals(CallSignal.Type.END, signaling.lastSignal().getType());
        assertEquals(1, media.stopCount);
    }

    @Test
    public void incomingCallCanBeAcceptedAndRemoteSdpAndIceAreDelivered() {
        signaling.emit(invite("call-in", "conversation-1", "peer-1"));
        scheduler.runCurrent();
        assertEquals(CallState.INCOMING_RINGING, session().getState());
        assertEquals(CallSession.Direction.INCOMING, session().getDirection());

        manager.accept("call-in");
        scheduler.runCurrent();
        assertEquals(CallState.CONNECTING, session().getState());
        assertEquals(CallSignal.Type.ACCEPT, signaling.lastSignal().getType());
        assertEquals(1, media.startCount);
        assertFalse(media.lastCaller);

        signaling.emit(remoteSignal(CallSignal.Type.SDP, "call-in"));
        signaling.emit(remoteSignal(CallSignal.Type.ICE, "call-in"));
        scheduler.runCurrent();
        assertEquals(2, media.remoteSignals.size());
        media.listener.onConnected();
        scheduler.runCurrent();
        assertEquals(CallState.ACTIVE, session().getState());
    }

    @Test
    public void illegalTransitionsAndUnknownCallIdsAreIgnored() {
        manager.accept("missing");
        manager.reject("missing");
        manager.cancel();
        manager.hangUp();
        manager.setMuted(true);
        manager.setSpeaker(true);
        signaling.emit(event(CallEvent.Type.CALL_ACCEPTED, "missing", null));
        scheduler.runCurrent();
        assertEquals(CallState.IDLE, session().getState());
        assertTrue(signaling.sent.isEmpty());

        manager.startOutgoing("conversation-1", "peer-1", "Peer");
        scheduler.runCurrent();
        signaling.emit(event(CallEvent.Type.CALL_ENDED, "other-call", "COMPLETED"));
        scheduler.runCurrent();
        assertEquals(CallState.OUTGOING_RINGING, session().getState());
        assertNull(session().getEndReason());
    }

    @Test
    public void ringingTerminalEventsAreIgnoredAfterTheCallIsActive() {
        startOutgoing();
        signaling.emit(event(CallEvent.Type.CALL_ACCEPTED, "call-1", null));
        scheduler.runCurrent();
        media.listener.onConnected();
        scheduler.runCurrent();

        signaling.emit(event(CallEvent.Type.CALL_TIMEOUT, "call-1", null));
        signaling.emit(event(CallEvent.Type.CALL_BUSY, "call-1", null));
        signaling.emit(event(CallEvent.Type.CALL_CANCELLED, "call-1", null));
        signaling.emit(event(CallEvent.Type.CALL_REJECTED, "call-1", null));
        scheduler.runCurrent();

        assertEquals(CallState.ACTIVE, session().getState());
        assertNull(session().getEndReason());
    }

    @Test
    public void rejectEndsIncomingCallAndSendsReject() {
        signaling.emit(invite("call-in", "conversation-1", "peer-1"));
        scheduler.runCurrent();
        manager.reject("call-in");
        scheduler.runCurrent();

        assertEquals(CallState.ENDED, session().getState());
        assertEquals(CallEndReason.REJECTED, session().getEndReason());
        assertEquals(CallSignal.Type.REJECT, signaling.lastSignal().getType());
    }

    @Test
    public void busyEndsTheMatchingOutgoingCall() {
        startOutgoing();
        signaling.emit(event(CallEvent.Type.CALL_BUSY, "call-1", null));
        scheduler.runCurrent();

        assertEquals(CallState.ENDED, session().getState());
        assertEquals(CallEndReason.BUSY, session().getEndReason());
    }

    @Test
    public void uncorrelatedCallErrorDoesNotEndTheCurrentCall() {
        startOutgoing();
        signaling.emit(error(null, null, "CALL_NOT_FOUND"));
        scheduler.runCurrent();

        assertEquals(CallState.OUTGOING_RINGING, session().getState());
        assertNull(session().getEndReason());
    }

    @Test
    public void matchingCallErrorEndsTheCurrentCall() {
        startOutgoing();
        signaling.emit(error("call-1", "conversation-1", "CALL_STATE_INVALID"));
        scheduler.runCurrent();

        assertEquals(CallState.ENDED, session().getState());
        assertEquals(CallEndReason.FAILED, session().getEndReason());
    }

    @Test
    public void clientRingTimeoutUsesServerTimeoutPlusGrace() {
        startOutgoing();
        scheduler.advanceBy(49L);
        assertEquals(CallState.OUTGOING_RINGING, session().getState());
        scheduler.advanceBy(1L);

        assertEquals(CallState.ENDED, session().getState());
        assertEquals(CallEndReason.MISSED, session().getEndReason());
    }

    @Test
    public void remoteHangUpIsTerminalAndStopsMediaOnce() {
        startOutgoing();
        signaling.emit(event(CallEvent.Type.CALL_ACCEPTED, "call-1", null));
        scheduler.runCurrent();
        media.listener.onConnected();
        scheduler.runCurrent();
        signaling.emit(event(CallEvent.Type.CALL_ENDED, "call-1", "COMPLETED"));
        scheduler.runCurrent();

        assertEquals(CallState.ENDED, session().getState());
        assertEquals(CallEndReason.COMPLETED, session().getEndReason());
        assertEquals(1, media.stopCount);
    }

    @Test
    public void duplicateTerminalEventsDoNotChangeOutcomeOrStopTwice() {
        startOutgoing();
        signaling.emit(event(CallEvent.Type.CALL_BUSY, "call-1", null));
        scheduler.runCurrent();
        signaling.emit(event(CallEvent.Type.CALL_TIMEOUT, "call-1", null));
        signaling.emit(event(CallEvent.Type.CALL_ENDED, "call-1", "FAILED"));
        scheduler.runCurrent();

        assertEquals(CallEndReason.BUSY, session().getEndReason());
        assertEquals(0, media.stopCount);
    }

    @Test
    public void signalingDisconnectDuringActiveCallEndsItAsFailed() {
        startOutgoing();
        signaling.emit(event(CallEvent.Type.CALL_ACCEPTED, "call-1", null));
        scheduler.runCurrent();
        media.listener.onConnected();
        scheduler.runCurrent();
        signaling.disconnect();
        scheduler.runCurrent();

        assertEquals(CallState.ENDED, session().getState());
        assertEquals(CallEndReason.FAILED, session().getEndReason());
        assertEquals(1, media.stopCount);
    }

    @Test
    public void serverInviteWinsAnOutgoingGlareRace() {
        startOutgoing();
        signaling.emit(invite("server-winner", "conversation-2", "peer-2"));
        scheduler.runCurrent();

        assertEquals(CallState.INCOMING_RINGING, session().getState());
        assertEquals("server-winner", session().getCallId());
        assertEquals(CallSignal.Type.CANCEL, signaling.sent.get(1).getType());
        assertEquals(0, media.stopCount);
    }

    private void startOutgoing() {
        manager.startOutgoing("conversation-1", "peer-1", "Peer");
        scheduler.runCurrent();
    }

    private CallSession session() {
        return manager.currentSessionForTest();
    }

    private static CallEvent event(CallEvent.Type type, String callId, String reason) {
        return new CallEvent(type, type.name(), callId, "conversation-1", null, null,
                null, reason, null, null);
    }

    private static CallEvent invite(String callId, String conversationId, String fromUserId) {
        return new CallEvent(CallEvent.Type.CALL_INVITE, "CALL_INVITE", callId,
                conversationId, fromUserId, null, null, null, null, null);
    }

    private static CallEvent error(String callId, String conversationId, String errorCode) {
        return new CallEvent(CallEvent.Type.CALL_ERROR, "CALL_ERROR", callId,
                conversationId, null, null, null, null, null, errorCode);
    }

    private static CallEvent remoteSignal(CallSignal.Type type, String callId) {
        CallSignal signal = type == CallSignal.Type.SDP
                ? new CallSignal(type, callId, new CallSignal.Sdp("offer", "remote-sdp"), null)
                : new CallSignal(type, callId, null,
                        new CallSignal.IceCandidate("audio", 0, "candidate:remote"));
        return new CallEvent(type == CallSignal.Type.SDP
                        ? CallEvent.Type.CALL_SDP : CallEvent.Type.CALL_ICE,
                type == CallSignal.Type.SDP ? "CALL_SDP" : "CALL_ICE",
                callId, "conversation-1", "peer-1", null, signal, null, null, null);
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
        final List<CallSignal> sent = new ArrayList<>();
        Listener listener;
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

        CallSignal lastSignal() {
            return sent.get(sent.size() - 1);
        }
    }

    private static final class FakeMediaEngine implements CallMediaEngine {
        int startCount;
        int stopCount;
        boolean lastCaller;
        Listener listener;
        final List<CallSignal> remoteSignals = new ArrayList<>();

        @Override
        public void start(boolean isCaller, IceConfig config, Listener listener) {
            startCount++;
            lastCaller = isCaller;
            this.listener = listener;
        }

        @Override
        public void onRemoteSdp(CallSignal.Sdp sdp) {
            remoteSignals.add(new CallSignal(CallSignal.Type.SDP, "call", sdp, null));
        }

        @Override
        public void onRemoteIce(CallSignal.IceCandidate candidate) {
            remoteSignals.add(new CallSignal(CallSignal.Type.ICE, "call", null, candidate));
        }

        @Override
        public void setMuted(boolean muted) { }

        @Override
        public void setSpeaker(boolean speakerOn) { }

        @Override
        public void stop() {
            stopCount++;
        }
    }
}
