package com.nexachat.call.ui;

import androidx.annotation.Nullable;
import com.nexachat.call.core.CallSession;
import com.nexachat.call.ui.FirebaseCallStatusDisplayAdapter.CallStatusDisplay;
import com.nexachat.core.realtime.ephemeral.FirebaseCallStatusListener;
import io.reactivex.rxjava3.disposables.Disposable;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Owns one call-screen projection listener for the Activity and authenticated bridge lifetimes. */
final class FirebaseCallStatusDisplaySession {
    @FunctionalInterface
    interface ListenerRegistrar {
        Disposable listen(String callId, FirebaseCallStatusListener listener);
    }

    private final Object lock = new Object();
    private final FirebaseCallStatusDisplayAdapter adapter;
    private final ListenerRegistrar registrar;
    private final Consumer<CallStatusDisplay> displayChanged;

    private boolean started;
    private boolean sourceReady;
    private long generation;
    @Nullable private CallSession session;
    @Nullable private String listeningCallId;
    @Nullable private CallStatusDisplay display;
    @Nullable private Disposable subscription;

    FirebaseCallStatusDisplaySession(
            FirebaseCallStatusDisplayAdapter adapter,
            ListenerRegistrar registrar,
            Consumer<CallStatusDisplay> displayChanged) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.registrar = Objects.requireNonNull(registrar, "registrar");
        this.displayChanged = Objects.requireNonNull(displayChanged, "displayChanged");
    }

    void start() {
        synchronized (lock) {
            started = true;
        }
        reconcile();
    }

    void onSourceReady() {
        synchronized (lock) {
            sourceReady = true;
        }
        reconcile();
    }

    void onSourceUnavailable() {
        synchronized (lock) {
            sourceReady = false;
        }
        detachAndClear();
    }

    void onCallChanged(@Nullable CallSession nextSession) {
        synchronized (lock) {
            session = nextSession;
        }
        reconcile();
    }

    void stop() {
        synchronized (lock) {
            started = false;
            sourceReady = false;
            session = null;
        }
        detachAndClear();
    }

    @Nullable
    CallStatusDisplay display() {
        synchronized (lock) {
            return display;
        }
    }

    private void reconcile() {
        final String callId;
        final long expectedGeneration;
        final Disposable previous;
        final boolean clearedDisplay;
        synchronized (lock) {
            if (!shouldListenLocked()) {
                previous = clearRegistrationLocked();
                clearedDisplay = clearDisplayLocked();
                callId = null;
                expectedGeneration = generation;
            } else {
                callId = session.getCallId();
                if (callId.equals(listeningCallId)
                        && subscription != null && !subscription.isDisposed()) return;
                previous = clearRegistrationLocked();
                clearedDisplay = clearDisplayLocked();
                expectedGeneration = generation;
            }
        }
        if (previous != null) previous.dispose();
        if (clearedDisplay) displayChanged.accept(null);
        if (callId == null) return;

        FirebaseCallStatusListener listener = new FirebaseCallStatusListener() {
            @Override
            public void onStatusChanged(
                    @Nullable String snapshotCallId,
                    String authenticatedUserId,
                    @Nullable Map<?, ?> payload,
                    long serverNowMillis) {
                updateDisplay(expectedGeneration, snapshotCallId, authenticatedUserId,
                        payload, serverNowMillis);
            }

            @Override
            public void onUnavailable() {
                listenerUnavailable(expectedGeneration);
            }
        };
        Disposable next = Objects.requireNonNull(
                registrar.listen(callId, listener), "listener registration");
        boolean accepted;
        synchronized (lock) {
            accepted = expectedGeneration == generation && shouldListenLocked()
                    && callId.equals(session.getCallId()) && !next.isDisposed();
            if (accepted) {
                listeningCallId = callId;
                subscription = next;
            }
        }
        if (!accepted) next.dispose();
    }

    private boolean shouldListenLocked() {
        return started && sourceReady && adapter.isEnabled() && session != null
                && CallActivityLaunchPolicy.isLive(session)
                && session.getCallId() != null && !session.getCallId().isBlank();
    }

    private void updateDisplay(
            long expectedGeneration,
            @Nullable String snapshotCallId,
            String authenticatedUserId,
            @Nullable Map<?, ?> payload,
            long serverNowMillis) {
        final CallSession currentSession;
        synchronized (lock) {
            if (expectedGeneration != generation || !shouldListenLocked()) return;
            currentSession = session;
        }
        Optional<CallStatusDisplay> adapted = adapter.adapt(
                snapshotCallId, currentSession.getCallId(), authenticatedUserId,
                payload, serverNowMillis);
        CallStatusDisplay nextDisplay = adapted.filter(value -> matches(value, currentSession))
                .orElse(null);
        boolean changed;
        synchronized (lock) {
            if (expectedGeneration != generation || session != currentSession) return;
            changed = !Objects.equals(display, nextDisplay);
            display = nextDisplay;
        }
        if (changed) displayChanged.accept(nextDisplay);
    }

    private static boolean matches(CallStatusDisplay value, CallSession session) {
        if (!same(value.getConversationId(), session.getConversationId())
                || !same(value.getPeerUserId(), session.getPeerUserId())
                || !same(value.getCallId(), session.getCallId())
                || session.getDirection() == null) {
            return false;
        }
        return value.isViewerCaller() == (session.getDirection() == CallSession.Direction.OUTGOING);
    }

    private void listenerUnavailable(long expectedGeneration) {
        boolean changed;
        Disposable previous;
        synchronized (lock) {
            if (expectedGeneration != generation) return;
            sourceReady = false;
            previous = clearRegistrationLocked();
            changed = clearDisplayLocked();
        }
        if (previous != null) previous.dispose();
        if (changed) displayChanged.accept(null);
    }

    private void detachAndClear() {
        final Disposable previous;
        final boolean changed;
        synchronized (lock) {
            previous = clearRegistrationLocked();
            changed = clearDisplayLocked();
        }
        if (previous != null) previous.dispose();
        if (changed) displayChanged.accept(null);
    }

    private Disposable clearRegistrationLocked() {
        generation++;
        listeningCallId = null;
        Disposable previous = subscription;
        subscription = null;
        return previous;
    }

    private boolean clearDisplayLocked() {
        boolean changed = display != null;
        display = null;
        return changed;
    }

    private static boolean same(String first, String second) {
        return first != null && second != null && first.equalsIgnoreCase(second);
    }
}
