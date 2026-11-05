package com.nexachat.call.core;

import com.nexachat.core.network.stomp.RealtimeSession;
import com.nexachat.core.network.stomp.ChatEvent;
import com.nexachat.core.network.stomp.StompClient;
import com.nexachat.core.network.stomp.StompCommand;
import com.nexachat.core.network.stomp.StompFrame;
import com.squareup.moshi.Moshi;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineStart;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.FlowCollector;

/** Java adapter for the existing authenticated raw-STOMP connection. */
public final class StompCallSignalingClient implements CallSignalingClient {
    static final String APP_CALLS_PREFIX = "/app/calls/";

    private final RealtimeSession realtimeSession;
    private final StompClient stompClient;
    private final CoroutineScope scope;
    private final CallFrameCodec codec;
    private final CallQueueSubscriptions subscriptions;
    private final Object lock = new Object();
    private final AtomicBoolean wasOpen = new AtomicBoolean(false);

    private Listener listener;
    private Job stateJob;
    private Job frameJob;
    private Job realtimeEventJob;
    private boolean started;

    public StompCallSignalingClient(
            RealtimeSession realtimeSession,
            StompClient stompClient,
            CoroutineScope scope,
            Moshi moshi) {
        this.realtimeSession = Objects.requireNonNull(realtimeSession, "realtimeSession");
        this.stompClient = Objects.requireNonNull(stompClient, "stompClient");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.codec = new CallFrameCodec(Objects.requireNonNull(moshi, "moshi"));
        this.subscriptions = new CallQueueSubscriptions(new CallQueueSubscriptions.Transport() {
            @Override
            public void subscribe(String destination) {
                StompCallSignalingClient.this.stompClient.subscribe(destination);
            }

            @Override
            public void unsubscribe(String destination) {
                StompCallSignalingClient.this.stompClient.unsubscribe(destination);
            }
        });
    }

    @Override
    public void start(Listener newListener) {
        Objects.requireNonNull(newListener, "listener");
        synchronized (lock) {
            if (started) {
                listener = newListener;
                return;
            }
            listener = newListener;
            started = true;
            subscriptions.start(false);
            frameJob = collect(stompClient.getFrames(), this::onFrame, CoroutineStart.UNDISPATCHED);
            realtimeEventJob = collect(
                    realtimeSession.getEvents(),
                    this::onRealtimeEvent,
                    CoroutineStart.UNDISPATCHED);
            stateJob = collect(
                    realtimeSession.getState(),
                    this::onConnectionState,
                    CoroutineStart.UNDISPATCHED);
        }
        realtimeSession.ensureConnected();
    }

    @Override
    public void stop() {
        Job oldStateJob;
        Job oldFrameJob;
        Job oldRealtimeEventJob;
        synchronized (lock) {
            if (!started) return;
            started = false;
            listener = null;
            oldStateJob = stateJob;
            oldFrameJob = frameJob;
            oldRealtimeEventJob = realtimeEventJob;
            stateJob = null;
            frameJob = null;
            realtimeEventJob = null;
            wasOpen.set(false);
            subscriptions.stop();
        }
        if (oldStateJob != null) oldStateJob.cancel((CancellationException) null);
        if (oldFrameJob != null) oldFrameJob.cancel((CancellationException) null);
        if (oldRealtimeEventJob != null) oldRealtimeEventJob.cancel((CancellationException) null);
    }

    @Override
    public boolean send(String conversationId, CallSignal signal) {
        if (conversationId == null || conversationId.trim().isEmpty()) return false;
        String body = codec.encodeSignal(signal);
        return body != null && stompClient.send(APP_CALLS_PREFIX + conversationId + "/signal", body);
    }

    @Override
    public boolean isConnected() {
        return stompClient.isOpen();
    }

    private void onConnectionState(StompClient.State state) {
        if (state instanceof StompClient.State.Open) {
            wasOpen.set(true);
            subscriptions.onOpen();
            return;
        }
        subscriptions.onClosed();
        if (wasOpen.getAndSet(false)) {
            Listener current;
            synchronized (lock) {
                current = started ? listener : null;
            }
            if (current != null) current.onDisconnected();
        }
    }

    private void onFrame(StompFrame frame) {
        if (!StompCommand.MESSAGE.equals(frame.getCommand())) return;
        String subscription = frame.getHeaders().get("subscription");
        String callsId = stompClient.subscriptionIdFor(CallQueueSubscriptions.CALLS_DESTINATION);
        if (callsId != null && callsId.equals(subscription)) {
            CallEvent event = codec.decodeEvent(frame.getBody());
            if (event != null) deliver(event);
        }
    }

    private void onRealtimeEvent(ChatEvent event) {
        if (!(event instanceof ChatEvent.SendRejected)) return;
        String code = ((ChatEvent.SendRejected) event).getCode();
        if (code == null) return;
        deliver(new CallEvent(
                CallEvent.Type.CALL_ERROR,
                "CALL_ERROR",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                code));
    }

    private void deliver(CallEvent event) {
        Listener current;
        synchronized (lock) {
            current = started ? listener : null;
        }
        if (current != null) current.onEvent(event);
    }

    private static final class FlowCollectorBridge<T> implements FlowCollector<T> {
        private final Consumer<T> consumer;

        FlowCollectorBridge(Consumer<T> consumer) {
            this.consumer = consumer;
        }

        @Override
        public Object emit(T value, Continuation<? super Unit> continuation) {
            consumer.accept(value);
            return Unit.INSTANCE;
        }
    }

    private <T> Job collect(Flow<T> flow, Consumer<T> consumer, CoroutineStart start) {
        return BuildersKt.launch(
                scope,
                (CoroutineContext) Dispatchers.getIO(),
                start,
                new Function2<CoroutineScope, Continuation<? super Unit>, Object>() {
                    @Override
                    public Object invoke(
                            CoroutineScope coroutineScope,
                            Continuation<? super Unit> continuation) {
                        return flow.collect(new FlowCollectorBridge<>(consumer), continuation);
                    }
                });
    }
}
