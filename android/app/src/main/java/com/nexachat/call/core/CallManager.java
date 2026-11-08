package com.nexachat.call.core;

import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Serializes every call-state mutation on one executor and posts LiveData
 * snapshots to the Android main thread.
 */
public final class CallManager implements AutoCloseable {
    private static final long RING_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(50);

    public interface CallListener {
        void onCallChanged(CallSession session);
        void onCallError(CallError error);
    }

    interface Cancellable {
        void cancel();
    }

    interface Scheduler {
        void execute(Runnable task);
        Cancellable schedule(Runnable task, long delayMs);
    }

    interface IceConfigSource {
        CallRepository.Result<IceConfig> load();
    }

    private final CallSignalingClient signaling;
    private final IceConfigSource iceConfigSource;
    private final CallPeerNameResolver peerNameResolver;
    private final Scheduler scheduler;
    private final Executor ioExecutor;
    private final LongSupplier clock;
    private final Supplier<String> callIdFactory;
    private final long ringTimeoutMs;
    private final boolean publishToAndroid;
    private final MutableLiveData<CallSession> session =
            new MutableLiveData<>(CallSession.idle());
    private final Set<CallListener> listeners = new CopyOnWriteArraySet<>();
    private final List<CallSignal> pendingRemoteSignals = new ArrayList<>();
    private final List<QueuedOutboundSignal> pendingOutboundSignals = new ArrayList<>();
    private final Handler mainHandler;

    private volatile CallSession currentSession = CallSession.idle();
    private CallMediaEngine mediaEngine;
    private Cancellable ringTimeout;
    private Cancellable reconnectGraceTimer;
    private boolean mediaStarted;
    private boolean mediaStartPending;
    private boolean closed;

    /** Creates a manager backed by the app's authenticated call transport and REST API. */
    public CallManager(CallSignalingClient signaling, CallRepository repository) {
        this(signaling, repository, fallbackOnlyNameResolver());
    }

    /** Creates a manager with an injectable local caller-name resolver. */
    public CallManager(
            CallSignalingClient signaling,
            CallRepository repository,
            CallPeerNameResolver peerNameResolver) {
        this(
                signaling,
                repository::getIceConfig,
                new ExecutorScheduler(),
                newIoExecutor(),
                System::currentTimeMillis,
                () -> UUID.randomUUID().toString(),
                RING_TIMEOUT_MS,
                true,
                peerNameResolver);
    }

    CallManager(
            CallSignalingClient signaling,
            IceConfigSource iceConfigSource,
            Scheduler scheduler,
            Executor ioExecutor,
            LongSupplier clock,
            Supplier<String> callIdFactory,
            long ringTimeoutMs,
            boolean publishToAndroid) {
        this(signaling, iceConfigSource, scheduler, ioExecutor, clock, callIdFactory,
                ringTimeoutMs, publishToAndroid, fallbackOnlyNameResolver());
    }

    CallManager(
            CallSignalingClient signaling,
            IceConfigSource iceConfigSource,
            Scheduler scheduler,
            Executor ioExecutor,
            LongSupplier clock,
            Supplier<String> callIdFactory,
            long ringTimeoutMs,
            boolean publishToAndroid,
            CallPeerNameResolver peerNameResolver) {
        this.signaling = signaling;
        this.iceConfigSource = iceConfigSource;
        this.peerNameResolver = peerNameResolver == null
                ? fallbackOnlyNameResolver() : peerNameResolver;
        this.scheduler = scheduler;
        this.ioExecutor = ioExecutor;
        this.clock = clock;
        this.callIdFactory = callIdFactory;
        this.ringTimeoutMs = Math.max(1L, ringTimeoutMs);
        this.publishToAndroid = publishToAndroid;
        this.mainHandler = publishToAndroid ? new Handler(Looper.getMainLooper()) : null;

        try {
            signaling.start(new CallSignalingClient.Listener() {
                @Override
                public void onEvent(CallEvent event) {
                    scheduler.execute(() -> handleEvent(event));
                }

                @Override
                public void onDisconnected() {
                    scheduler.execute(CallManager.this::handleSignalingDisconnected);
                }

                @Override
                public void onConnected() {
                    scheduler.execute(CallManager.this::handleSignalingConnected);
                }
            });
        } catch (RuntimeException exception) {
            scheduler.execute(() -> notifyError(new CallError(CallError.Code.NETWORK)));
        }
    }

    /** Starts an outgoing direct call. A second call is ignored while one is active. */
    public void startOutgoing(String conversationId, String peerUserId, String peerName) {
        scheduler.execute(() -> {
            if (closed || isCallInProgress(currentSession.getState())) return;
            if (blank(conversationId) || blank(peerUserId)) {
                notifyError(new CallError(CallError.Code.VALIDATION_FAILED));
                return;
            }
            String callId = callIdFactory.get();
            if (blank(callId)) {
                notifyError(new CallError(CallError.Code.UNKNOWN));
                return;
            }
            setSession(new CallSession(
                    callId,
                    conversationId,
                    peerUserId,
                    peerName,
                    CallSession.Direction.OUTGOING,
                    CallState.OUTGOING_RINGING,
                    null,
                    clock.getAsLong(),
                    null,
                    false,
                    false));
            scheduleRingTimeout(callId);
            if (!send(conversationId, CallSignal.Type.INVITE, callId)) {
                finish(CallEndReason.FAILED, null, new CallError(CallError.Code.NETWORK));
            }
        });
    }

    /**
     * Hydrates an incoming call session from a background push notification
     * before STOMP signaling has connected. Holds the session in PENDING_INCOMING
     * with a 15s grace timer waiting for the STOMP INVITE frame.
     */
    public void hydrateIncomingCall(
            String callId,
            String conversationId,
            String callerId,
            String callerName,
            long serverTs) {
        scheduler.execute(() -> {
            if (closed || isCallInProgress(currentSession.getState())) return;
            if (blank(callId) || blank(conversationId) || blank(callerId)) return;
            String name = callerName != null && !callerName.isBlank()
                    ? callerName
                    : unknownCallerDisplayName(callerId);
            setSession(new CallSession(
                    callId,
                    conversationId,
                    callerId,
                    name,
                    CallSession.Direction.INCOMING,
                    CallState.PENDING_INCOMING,
                    null,
                    serverTs > 0 ? serverTs : clock.getAsLong(),
                    null,
                    false,
                    false));
            cancelRingTimeout();
            ringTimeout = scheduler.schedule(
                    () -> finish(CallEndReason.MISSED, CallSignal.Type.CANCEL, null),
                    15_000L);
        });
    }

    /** Accepts the matching incoming call and starts the callee media setup. */
    public void accept(String callId) {
        scheduler.execute(() -> {
            if (!matches(callId) || (currentSession.getState() != CallState.INCOMING_RINGING
                    && currentSession.getState() != CallState.PENDING_INCOMING)) return;
            cancelRingTimeout();
            cancelReconnectGraceTimer();
            if (!send(currentSession.getConversationId(), CallSignal.Type.ACCEPT, callId)) {
                finish(CallEndReason.FAILED, null, new CallError(CallError.Code.NETWORK));
                return;
            }
            transition(CallState.CONNECTING, null, currentSession.getAnsweredAtMs());
            beginMedia(callId, false);
        });
    }

    /** Declines the matching incoming call. */
    public void reject(String callId) {
        scheduler.execute(() -> {
            if (!matches(callId) || (currentSession.getState() != CallState.INCOMING_RINGING
                    && currentSession.getState() != CallState.PENDING_INCOMING)) return;
            finish(CallEndReason.REJECTED, CallSignal.Type.REJECT, null);
        });
    }

    /** Cancels an outgoing call that is still ringing. */
    public void cancel() {
        scheduler.execute(() -> {
            if (currentSession.getState() != CallState.OUTGOING_RINGING) return;
            finish(CallEndReason.CANCELLED, CallSignal.Type.CANCEL, null);
        });
    }

    /** Ends the current call, using CANCEL/REJECT while ringing and END after acceptance. */
    public void hangUp() {
        scheduler.execute(() -> {
            CallState state = currentSession.getState();
            if (!isCallInProgress(state)) return;
            CallSignal.Type signalType;
            CallEndReason reason;
            if (state == CallState.OUTGOING_RINGING) {
                signalType = CallSignal.Type.CANCEL;
                reason = CallEndReason.CANCELLED;
            } else if (state == CallState.INCOMING_RINGING) {
                signalType = CallSignal.Type.REJECT;
                reason = CallEndReason.REJECTED;
            } else {
                signalType = CallSignal.Type.END;
                reason = state == CallState.ACTIVE
                        ? CallEndReason.COMPLETED
                        : CallEndReason.CANCELLED;
            }
            finish(reason, signalType, null);
        });
    }

    /** Changes the local microphone mute state. */
    public void setMuted(boolean muted) {
        scheduler.execute(() -> {
            if (!isCallInProgress(currentSession.getState())) return;
            currentSession = currentSession.withAudio(muted, currentSession.isSpeakerOn());
            publishSession();
            if (mediaEngine != null) mediaEngine.setMuted(muted);
        });
    }

    /** Changes the local audio output route. */
    public void setSpeaker(boolean speakerOn) {
        scheduler.execute(() -> {
            if (!isCallInProgress(currentSession.getState())) return;
            currentSession = currentSession.withAudio(currentSession.isMuted(), speakerOn);
            publishSession();
            if (mediaEngine != null) mediaEngine.setSpeaker(speakerOn);
        });
    }

    /** Returns the observable session; every value is posted on the main thread. */
    public LiveData<CallSession> session() {
        return session;
    }

    /** Adds a listener whose callbacks are delivered on the main thread. */
    public void addListener(CallListener listener) {
        if (listener != null) listeners.add(listener);
    }

    /** Removes a listener previously registered with {@link #addListener(CallListener)}. */
    public void removeListener(CallListener listener) {
        if (listener != null) listeners.remove(listener);
    }

    /** Attaches the UI-owned audio engine used for the current or next call. */
    public void attachMediaEngine(CallMediaEngine mediaEngine) {
        scheduler.execute(() -> {
            if (this.mediaEngine == mediaEngine) return;
            if (this.mediaEngine != null) this.mediaEngine.stop();
            this.mediaEngine = mediaEngine;
            if (mediaEngine != null) {
                mediaEngine.setMuted(currentSession.isMuted());
                mediaEngine.setSpeaker(currentSession.isSpeakerOn());
            }
        });
    }

    @Override
    public void close() {
        scheduler.execute(() -> {
            if (closed) return;
            if (isCallInProgress(currentSession.getState())) {
                finish(CallEndReason.FAILED, null, null);
            }
            closed = true;
            cancelRingTimeout();
            stopMedia();
            signaling.stop();
            listeners.clear();
            if (scheduler instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) scheduler).close();
                } catch (Exception ignored) {
                    // Teardown is best effort after the call is terminal.
                }
            }
            if (ioExecutor instanceof ExecutorService) {
                ((ExecutorService) ioExecutor).shutdownNow();
            }
        });
    }

    private void handleEvent(CallEvent event) {
        if (closed || event == null || event.getType() == null) return;
        if (event.getType() == CallEvent.Type.CALL_INVITE) {
            handleInvite(event);
            return;
        }
        if (event.getType() == CallEvent.Type.CALL_ERROR) {
            handleCallError(event);
            return;
        }
        if (!matches(event.getCallId()) || currentSession.getState() == CallState.ENDED) return;

        switch (event.getType()) {
            case CALL_ACCEPTED:
                if (currentSession.getState() == CallState.OUTGOING_RINGING) {
                    cancelRingTimeout();
                    transition(CallState.CONNECTING, null, currentSession.getAnsweredAtMs());
                    beginMedia(currentSession.getCallId(), true);
                }
                break;
            case CALL_REJECTED:
                if (currentSession.getState() == CallState.OUTGOING_RINGING) {
                    finish(CallEndReason.REJECTED, null, null);
                }
                break;
            case CALL_CANCELLED:
                if (isRinging(currentSession.getState())) {
                    finish(CallEndReason.CANCELLED, null, null);
                }
                break;
            case CALL_BUSY:
                if (currentSession.getState() == CallState.OUTGOING_RINGING) {
                    finish(CallEndReason.BUSY, null, new CallError(CallError.Code.BUSY));
                }
                break;
            case CALL_TIMEOUT:
                if (isRinging(currentSession.getState())) {
                    finish(CallEndReason.MISSED, null, null);
                }
                break;
            case CALL_ENDED:
                finish(parseEndReason(event.getReason()), null, null);
                break;
            case CALL_SDP:
            case CALL_ICE:
                handleRemoteSignal(event.getSignal());
                break;
            default:
                break;
        }
    }

    private void handleInvite(CallEvent event) {
        String callId = event.getCallId();
        if (blank(callId) || blank(event.getConversationId()) || blank(event.getFromUserId())) return;
        if (callId.equals(currentSession.getCallId())) {
            if (currentSession.getState() == CallState.PENDING_INCOMING) {
                cancelRingTimeout();
                transition(CallState.INCOMING_RINGING, null, null);
                scheduleRingTimeout(callId);
                String fallbackName = currentSession.getPeerDisplayName() != null
                        ? currentSession.getPeerDisplayName()
                        : unknownCallerDisplayName(event.getFromUserId());
                resolveIncomingPeerName(callId, event.getConversationId(), event.getFromUserId(), fallbackName);
            }
            return;
        }

        CallState state = currentSession.getState();
        if (state == CallState.OUTGOING_RINGING || state == CallState.PENDING_INCOMING) {
            // A server-delivered INVITE is the server's glare winner.
            send(currentSession.getConversationId(), CallSignal.Type.CANCEL, currentSession.getCallId());
            cancelRingTimeout();
            pendingRemoteSignals.clear();
        } else if (isCallInProgress(state)) {
            return;
        }

        String fallbackName = unknownCallerDisplayName(event.getFromUserId());
        setSession(new CallSession(
                callId,
                event.getConversationId(),
                event.getFromUserId(),
                fallbackName,
                CallSession.Direction.INCOMING,
                CallState.INCOMING_RINGING,
                null,
                parseTimestamp(event.getServerTs(), clock.getAsLong()),
                null,
                false,
                false));
        scheduleRingTimeout(callId);
        resolveIncomingPeerName(callId, event.getConversationId(), event.getFromUserId(), fallbackName);
    }

    private void resolveIncomingPeerName(
            String callId,
            String conversationId,
            String peerUserId,
            String fallbackName) {
        try {
            ioExecutor.execute(() -> {
                try {
                    peerNameResolver.resolve(conversationId, peerUserId, displayName ->
                            scheduler.execute(() -> updateIncomingPeerName(
                                    callId, conversationId, peerUserId, fallbackName, displayName)));
                } catch (RuntimeException ignored) {
                    // The already-published localized fallback remains safe if local lookup fails.
                }
            });
        } catch (RuntimeException ignored) {
            // The already-published localized fallback remains safe if the IO executor is unavailable.
        }
    }

    private void updateIncomingPeerName(
            String callId,
            String conversationId,
            String peerUserId,
            String fallbackName,
            String displayName) {
        if (!matches(callId) || currentSession.getState() == CallState.ENDED
                || currentSession.getDirection() != CallSession.Direction.INCOMING
                || !same(conversationId, currentSession.getConversationId())
                || !same(peerUserId, currentSession.getPeerUserId())) return;
        String safeName = safeDisplayName(displayName, peerUserId);
        if (safeName == null) safeName = fallbackName;
        if (same(safeName, currentSession.getPeerDisplayName())) return;
        currentSession = currentSession.withPeerDisplayName(safeName);
        publishSession();
    }

    private String unknownCallerDisplayName(String peerUserId) {
        String fallback = null;
        try {
            fallback = peerNameResolver.unknownCallerDisplayName();
        } catch (RuntimeException ignored) {
            // Fall through to a neutral non-identifier label.
        }
        String safeFallback = safeDisplayName(fallback, peerUserId);
        return safeFallback == null ? "Unknown caller" : safeFallback;
    }

    private static String safeDisplayName(String displayName, String peerUserId) {
        if (blank(displayName)) return null;
        String value = displayName.trim();
        if (!blank(peerUserId) && peerUserId.trim().equalsIgnoreCase(value)) return null;
        return value;
    }

    private static boolean same(String left, String right) {
        return left != null && left.equals(right);
    }

    private static CallPeerNameResolver fallbackOnlyNameResolver() {
        return new CallPeerNameResolver() {
            @Override
            public String unknownCallerDisplayName() {
                return "Unknown caller";
            }

            @Override
            public void resolve(String conversationId, String peerUserId, Callback callback) {
                if (callback != null) callback.onResolved(null);
            }
        };
    }

    private void handleCallError(CallEvent event) {
        // The shared /user/queue/errors envelope currently carries no callId.
        // Do not let a delayed error from an earlier SEND end a newer call.
        if (!matches(event.getCallId())) return;
        CallError error = CallError.fromServerCode(event.getErrorCode());
        notifyError(error);
        if (isCallInProgress(currentSession.getState())) {
            finish(error.getCode() == CallError.Code.BUSY
                    ? CallEndReason.BUSY
                    : CallEndReason.FAILED, null, null);
        }
    }

    private void handleRemoteSignal(CallSignal signal) {
        if (signal == null || !matches(signal.getCallId())) return;
        CallState state = currentSession.getState();
        if (state != CallState.CONNECTING && state != CallState.ACTIVE) return;
        if (mediaEngine == null || !mediaStarted) {
            pendingRemoteSignals.add(signal);
            return;
        }
        deliverRemoteSignal(signal);
    }

    private void deliverRemoteSignal(CallSignal signal) {
        if (mediaEngine == null) return;
        try {
            if (signal.getType() == CallSignal.Type.SDP && signal.getSdp() != null) {
                mediaEngine.onRemoteSdp(signal.getSdp());
            } else if (signal.getType() == CallSignal.Type.ICE && signal.getCandidate() != null) {
                mediaEngine.onRemoteIce(signal.getCandidate());
            }
        } catch (RuntimeException exception) {
            finish(CallEndReason.FAILED, null, new CallError(CallError.Code.MEDIA_UNAVAILABLE));
        }
    }

    private void beginMedia(String callId, boolean isCaller) {
        if (mediaStartPending || mediaStarted) return;
        if (mediaEngine == null) {
            finish(CallEndReason.FAILED, null, new CallError(CallError.Code.MEDIA_UNAVAILABLE));
            return;
        }
        mediaStartPending = true;
        ioExecutor.execute(() -> {
            CallRepository.Result<IceConfig> result;
            try {
                result = iceConfigSource.load();
            } catch (RuntimeException exception) {
                result = CallRepository.Result.failure(new CallError(CallError.Code.NETWORK));
            }
            CallRepository.Result<IceConfig> finalResult = result;
            scheduler.execute(() -> finishMediaStart(callId, isCaller, finalResult));
        });
    }

    private void finishMediaStart(
            String callId,
            boolean isCaller,
            CallRepository.Result<IceConfig> result) {
        if (!matches(callId) || currentSession.getState() != CallState.CONNECTING) return;
        mediaStartPending = false;
        if (result == null || !result.isSuccess() || result.getValue() == null) {
            CallError error = result == null || result.getError() == null
                    ? new CallError(CallError.Code.NETWORK)
                    : result.getError();
            finish(CallEndReason.FAILED, null, error);
            return;
        }
        if (mediaEngine == null) {
            finish(CallEndReason.FAILED, null, new CallError(CallError.Code.MEDIA_UNAVAILABLE));
            return;
        }
        String startedCallId = currentSession.getCallId();
        try {
            mediaEngine.start(isCaller, result.getValue(), new CallMediaEngine.Listener() {
                @Override
                public void onLocalSdp(CallSignal.Sdp sdp) {
                    scheduler.execute(() -> sendLocalSignal(startedCallId, CallSignal.Type.SDP, sdp, null));
                }

                @Override
                public void onLocalIce(CallSignal.IceCandidate candidate) {
                    scheduler.execute(() -> sendLocalSignal(startedCallId, CallSignal.Type.ICE, null, candidate));
                }

                @Override
                public void onConnected() {
                    scheduler.execute(() -> markConnected(startedCallId));
                }

                @Override
                public void onFailed(String reason) {
                    scheduler.execute(() -> {
                        if (matches(startedCallId) && isCallInProgress(currentSession.getState())) {
                            finish(CallEndReason.FAILED, null,
                                    new CallError(CallError.Code.MEDIA_UNAVAILABLE));
                        }
                    });
                }
            });
            mediaStarted = true;
            mediaEngine.setMuted(currentSession.isMuted());
            mediaEngine.setSpeaker(currentSession.isSpeakerOn());
            List<CallSignal> queued = new ArrayList<>(pendingRemoteSignals);
            pendingRemoteSignals.clear();
            for (CallSignal pending : queued) deliverRemoteSignal(pending);
        } catch (RuntimeException exception) {
            finish(CallEndReason.FAILED, null, new CallError(CallError.Code.MEDIA_UNAVAILABLE));
        }
    }

    private void cancelReconnectGraceTimer() {
        if (reconnectGraceTimer != null) {
            reconnectGraceTimer.cancel();
            reconnectGraceTimer = null;
        }
    }

    private void sendLocalSignal(
            String callId,
            CallSignal.Type type,
            CallSignal.Sdp sdp,
            CallSignal.IceCandidate candidate) {
        if (!matches(callId)) return;
        CallState state = currentSession.getState();
        if (state != CallState.CONNECTING
                && state != CallState.ACTIVE
                && state != CallState.RECONNECTING_NETWORK) return;
        CallSignal signal = new CallSignal(type, callId, sdp, candidate);
        if (state == CallState.RECONNECTING_NETWORK) {
            pendingOutboundSignals.add(new QueuedOutboundSignal(currentSession.getConversationId(), signal));
            return;
        }
        if (!signaling.send(currentSession.getConversationId(), signal)) {
            finish(CallEndReason.FAILED, null, new CallError(CallError.Code.NETWORK));
        }
    }

    private void markConnected(String callId) {
        if (!matches(callId) || currentSession.getState() != CallState.CONNECTING) return;
        transition(CallState.ACTIVE, null, clock.getAsLong());
    }

    private void handleSignalingDisconnected() {
        if (currentSession.getState() == CallState.ACTIVE) {
            transition(CallState.RECONNECTING_NETWORK, null, currentSession.getAnsweredAtMs());
            cancelReconnectGraceTimer();
            reconnectGraceTimer = scheduler.schedule(() -> {
                if (currentSession.getState() == CallState.RECONNECTING_NETWORK) {
                    finish(CallEndReason.FAILED, null, new CallError(CallError.Code.NETWORK));
                }
            }, 15_000L);
        } else if (isCallInProgress(currentSession.getState())
                && currentSession.getState() != CallState.RECONNECTING_NETWORK) {
            finish(CallEndReason.FAILED, null, new CallError(CallError.Code.NETWORK));
        }
    }

    private void handleSignalingConnected() {
        if (currentSession.getState() == CallState.RECONNECTING_NETWORK) {
            cancelReconnectGraceTimer();
            transition(CallState.ACTIVE, null, currentSession.getAnsweredAtMs());
            if (mediaEngine != null) {
                mediaEngine.restartIce();
            }
            List<QueuedOutboundSignal> queued = new ArrayList<>(pendingOutboundSignals);
            pendingOutboundSignals.clear();
            for (QueuedOutboundSignal item : queued) {
                signaling.send(item.conversationId, item.signal);
            }
        }
    }

    private boolean send(String conversationId, CallSignal.Type type, String callId) {
        return signaling.send(conversationId, CallSignal.control(type, callId));
    }

    private void finish(CallEndReason reason, CallSignal.Type signalType, CallError error) {
        if (!isCallInProgress(currentSession.getState())) return;
        CallSession old = currentSession;
        boolean sent = signalType == null
                || send(old.getConversationId(), signalType, old.getCallId());
        cancelRingTimeout();
        cancelReconnectGraceTimer();
        mediaStartPending = false;
        pendingRemoteSignals.clear();
        pendingOutboundSignals.clear();
        stopMedia();
        transition(CallState.ENDED, reason, old.getAnsweredAtMs());
        if (error != null) {
            notifyError(error);
        } else if (!sent) {
            notifyError(new CallError(CallError.Code.NETWORK));
        }
    }

    private void transition(CallState state, CallEndReason reason, Long answeredAtMs) {
        CallSession old = currentSession;
        currentSession = new CallSession(
                old.getCallId(),
                old.getConversationId(),
                old.getPeerUserId(),
                old.getPeerDisplayName(),
                old.getDirection(),
                state,
                reason,
                old.getStartedAtMs(),
                answeredAtMs,
                old.isMuted(),
                old.isSpeakerOn());
        publishSession();
    }

    private void setSession(CallSession next) {
        currentSession = next;
        publishSession();
    }

    private void publishSession() {
        if (!publishToAndroid) return;
        session.postValue(currentSession);
        for (CallListener listener : listeners) {
            CallSession value = currentSession;
            mainHandler.post(() -> {
                if (listeners.contains(listener)) listener.onCallChanged(value);
            });
        }
    }

    private void notifyError(CallError error) {
        if (!publishToAndroid) return;
        for (CallListener listener : listeners) {
            mainHandler.post(() -> {
                if (listeners.contains(listener)) listener.onCallError(error);
            });
        }
    }

    private void scheduleRingTimeout(String callId) {
        cancelRingTimeout();
        ringTimeout = scheduler.schedule(() -> {
            if (matches(callId) && (currentSession.getState() == CallState.OUTGOING_RINGING
                    || currentSession.getState() == CallState.INCOMING_RINGING)) {
                finish(CallEndReason.MISSED, null, null);
            }
        }, ringTimeoutMs);
    }

    private void cancelRingTimeout() {
        if (ringTimeout != null) {
            ringTimeout.cancel();
            ringTimeout = null;
        }
    }

    private void stopMedia() {
        boolean wasStarted = mediaStarted;
        mediaStarted = false;
        mediaStartPending = false;
        if (!wasStarted || mediaEngine == null) {
            return;
        }
        try {
            mediaEngine.stop();
        } catch (RuntimeException ignored) {
            // A terminal state must not be held open by engine teardown.
        }
        mediaEngine = null;
    }

    private boolean matches(String callId) {
        return callId != null && callId.equals(currentSession.getCallId());
    }

    static boolean isCallInProgress(CallState state) {
        return state == CallState.OUTGOING_RINGING
                || state == CallState.INCOMING_RINGING
                || state == CallState.PENDING_INCOMING
                || state == CallState.CONNECTING
                || state == CallState.ACTIVE
                || state == CallState.RECONNECTING_NETWORK;
    }

    private static boolean isRinging(CallState state) {
        return state == CallState.OUTGOING_RINGING
                || state == CallState.INCOMING_RINGING
                || state == CallState.PENDING_INCOMING;
    }

    private static CallEndReason parseEndReason(String reason) {
        if (reason == null) return CallEndReason.FAILED;
        try {
            return CallEndReason.valueOf(reason.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return CallEndReason.FAILED;
        }
    }

    private static long parseTimestamp(String timestamp, long fallback) {
        if (timestamp == null) return fallback;
        try {
            return java.time.Instant.parse(timestamp).toEpochMilli();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    CallSession currentSessionForTest() {
        return currentSession;
    }

    private static ExecutorService newIoExecutor() {
        return Executors.newSingleThreadExecutor(daemonThreadFactory("nexachat-call-io"));
    }

    private static ThreadFactory daemonThreadFactory(String name) {
        return task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class ExecutorScheduler implements Scheduler, AutoCloseable {
        private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
                daemonThreadFactory("nexachat-call-state"));

        @Override
        public void execute(Runnable task) {
            executor.execute(task);
        }

        @Override
        public Cancellable schedule(Runnable task, long delayMs) {
            ScheduledFuture<?> future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    private static final class QueuedOutboundSignal {
        final String conversationId;
        final CallSignal signal;

        QueuedOutboundSignal(String conversationId, CallSignal signal) {
            this.conversationId = conversationId;
            this.signal = signal;
        }
    }
}
