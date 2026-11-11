package com.nexachat.core.network.stomp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.nexachat.core.common.AppConfig;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.core.storage.Tokens;
import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.disposables.SerialDisposable;
import io.reactivex.rxjava3.processors.PublishProcessor;
import io.reactivex.rxjava3.processors.FlowableProcessor;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.LinkedHashMap;
import java.net.SocketTimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.LongPredicate;
import javax.inject.Inject;
import javax.inject.Singleton;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Raw OkHttp WebSocket plus STOMP 1.2 client. Reconnect policy stays in RealtimeSession. */
@Singleton
public final class StompClient {
    @FunctionalInterface
    public interface SessionActionGate {
        /** Runs the action only while this connection's session is current. */
        boolean runIfCurrent(@NonNull Runnable action);
    }


    /** One connection attempt lifecycle. Terminal states are {@link State.Failed} or {@link State.Closed}. */
    public sealed interface State permits State.Connecting, State.Open, State.Failed, State.Closed {

        final class Connecting implements State {
            public static final Connecting INSTANCE = new Connecting();

            private Connecting() {
            }

            @Override
            public String toString() {
                return "Connecting";
            }
        }

        /** CONNECTED frame received — the STOMP session is live. */
        final class Open implements State {
            public static final Open INSTANCE = new Open();

            private Open() {
            }

            @Override
            public String toString() {
                return "Open";
            }
        }

        /** The attempt died before or after Open. */
        record Failed(@Nullable String reason, boolean auth, boolean wakeRetryable) implements State {
            public Failed(@Nullable String reason) {
                this(reason, false, false);
            }

            public Failed(@Nullable String reason, boolean auth) {
                this(reason, auth, false);
            }

            @Nullable
            public String getReason() {
                return reason;
            }

            public boolean isAuth() {
                return auth;
            }

            public boolean isWakeRetryable() {
                return wakeRetryable;
            }

            @Override
            public String toString() {
                return "Failed(reason=" + reason + ", auth=" + auth
                        + ", wakeRetryable=" + wakeRetryable + ")";
            }
        }

        /** Remote close handshake observed (code and reason per the WebSocket spec). */
        record Closed(int code, @NonNull String reason) implements State {
            @NonNull
            public String getReason() {
                return reason;
            }

            @Override
            public String toString() {
                return "Closed(code=" + code + ", reason=" + reason + ")";
            }
        }

        /** Convenient singleton values for the common connection states. */
        Connecting Connecting = StompClient.State.Connecting.INSTANCE;
        Open Open = StompClient.State.Open.INSTANCE;
    }

    /** Callback surface for emitting connection state changes. */
    public interface StateSink {
        boolean trySend(@NonNull State state);

        void close();
    }

    private static final String HEARTBEAT_FRAME = "\n";
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;

    public static final long HEARTBEAT_MS = 10_000L;
    public static final long DEAD_AFTER_MS = 30_000L;
    public static final long CONNECT_ACK_TIMEOUT_MS = 8_000L;
    public static final int NORMAL_CLOSURE_CODE = 1000;
    @NonNull
    public static final String CLOSE_REASON = "client closing";
    @NonNull
    public static final String REASON_NO_TOKEN = "NO_TOKEN";
    @NonNull
    public static final String REASON_ERROR_FRAME = "ERROR_FRAME";
    @NonNull
    public static final String REASON_CONNECT_TIMEOUT = "CONNECT_TIMEOUT";
    @NonNull
    public static final String REASON_OFFLINE = "OFFLINE";

    private final OkHttpClient okHttpClient;
    private final TokenStore tokenStore;
    private final HttpUrl wsUrl;
    private final FlowableProcessor<StompFrame> incomingFrames = PublishProcessor.<StompFrame>create().toSerialized();
    private final ConcurrentHashMap<String, String> subscriptionIds = new ConcurrentHashMap<>();
    private final AtomicInteger subscriptionCounter = new AtomicInteger();
    private final AtomicLong connectAttemptSeq = new AtomicLong();
    private final Object attemptSequenceLock = new Object();

    @Nullable
    private volatile java.util.function.Consumer<String> connectBearerObserver;

    @Nullable
    private volatile BiConsumer<WebSocketListener, Long> testListenerCapture;

    @Nullable
    private volatile BiFunction<WebSocketListener, Long, WebSocket> testWebSocketFactory;

    @Nullable
    private volatile WebSocket socket;
    private volatile boolean open;
    private volatile long lastInboundMs;

    @Inject
    public StompClient(@NonNull OkHttpClient okHttpClient, @NonNull TokenStore tokenStore) {
        this.okHttpClient = okHttpClient;
        this.tokenStore = tokenStore;
        this.wsUrl = WsEndpoint.fromBaseUrl(AppConfig.API_BASE_URL);
    }

    /** Every decoded server frame. Producers never suspend. */
    @NonNull
    public Flowable<StompFrame> getFrames() {
        return incomingFrames.onBackpressureBuffer(256, () -> { }, io.reactivex.rxjava3.core.BackpressureOverflowStrategy.DROP_OLDEST);
    }

    /** Opens a socket using the persisted access token. */
    @NonNull
    public Flowable<State> connect() {
        return connect(null);
    }

    /**
     * Opens a socket with an explicit attempt bearer after realtime preflight.
     * When {@code accessToken} is null, falls back to {@link TokenStore#readTokens()}.
     */
    @NonNull
    public Flowable<State> connect(@Nullable String accessToken) {
        return connect(accessToken, action -> {
            action.run();
            return true;
        });
    }

    @NonNull
    public Flowable<State> connect(
            @Nullable String accessToken,
            @NonNull SessionActionGate sessionActionGate) {
        final long attemptId;
        synchronized (attemptSequenceLock) {
            attemptId = connectAttemptSeq.incrementAndGet();
        }
        AttemptLiveness liveness = new AttemptLiveness(
                connectAttemptSeq, attemptId, sessionActionGate, attemptSequenceLock);
        LongPredicate stillCurrent = ignored -> liveness.isCurrent();
        return Flowable.create(emitter -> {
            SerialDisposable lifecycle = new SerialDisposable();
            emitter.setCancellable(() -> {
                liveness.invalidate();
                lifecycle.dispose();
            });
            if (!tryEmitState(emitter, liveness, State.Connecting.INSTANCE)) {
                completeIfCurrent(emitter, liveness);
                return;
            }
            SerialDisposable readHolder = new SerialDisposable();
            lifecycle.set(readHolder);
            if (accessToken != null) {
                beginAttempt(accessToken, attemptId, stillCurrent, liveness, emitter, lifecycle);
                return;
            }
            Disposable read = tokenStore.readTokens()
                    .subscribeOn(Schedulers.io())
                    .doOnComplete(() -> {
                        emitStateAndCompleteIfCurrent(
                                emitter, liveness, new State.Failed(REASON_NO_TOKEN));
                    })
                    .subscribe(tokens -> {
                        if (!stillCurrent.test(attemptId)) {
                            completeIfCurrent(emitter, liveness);
                            return;
                        }
                        String token = tokens.getAccessToken();
                        if (token.isBlank()) {
                            emitStateAndCompleteIfCurrent(
                                    emitter, liveness, new State.Failed(REASON_NO_TOKEN));
                            return;
                        }
                        beginAttempt(token, attemptId, stillCurrent, liveness, emitter, lifecycle);
                    }, failure -> {
                        if (!stillCurrent.test(attemptId)) {
                            completeIfCurrent(emitter, liveness);
                            return;
                        }
                        emitStateAndCompleteIfCurrent(
                                emitter, liveness, new State.Failed(failure.getMessage()));
                    });
            readHolder.set(read);
        }, BackpressureStrategy.LATEST);
    }

    /** Observes the bearer wired into the STOMP CONNECT frame (tests only). */
    @VisibleForTesting
    void setConnectBearerObserverForTests(
            @Nullable java.util.function.Consumer<String> observer) {
        connectBearerObserver = observer;
    }

    @VisibleForTesting
    void setTestListenerCapture(@Nullable BiConsumer<WebSocketListener, Long> capture) {
        testListenerCapture = capture;
    }

    @VisibleForTesting
    void setTestWebSocketFactoryForTests(
            @Nullable BiFunction<WebSocketListener, Long, WebSocket> factory) {
        testWebSocketFactory = factory;
    }

    private void beginAttempt(
            @NonNull String token,
            long attemptId,
            @NonNull LongPredicate stillCurrent,
            @NonNull AttemptLiveness liveness,
            @NonNull io.reactivex.rxjava3.core.FlowableEmitter<State> emitter,
            @NonNull SerialDisposable lifecycle) {
        StateSink sink = new StateSink() {
            @Override public boolean trySend(@NonNull State state) {
                return tryEmitState(emitter, liveness, state);
            }

            @Override public void close() {
                completeIfCurrent(emitter, liveness);
            }
        };
        try {
            AutoCloseable cleanup = startConnection(token, sink, stillCurrent, attemptId, liveness);
            lifecycle.set(Disposable.fromAction(() -> {
                try {
                    cleanup.close();
                } catch (Exception ignored) {
                }
            }));
        } catch (RuntimeException failure) {
            emitStateAndCompleteIfCurrent(
                    emitter, liveness, new State.Failed(failure.getMessage()));
        }
    }

    private static boolean tryEmitState(
            @NonNull io.reactivex.rxjava3.core.FlowableEmitter<State> emitter,
            @NonNull AttemptLiveness liveness,
            @NonNull State state) {
        AtomicBoolean emitted = new AtomicBoolean();
        boolean current = liveness.runIfCurrent(() -> {
            if (!emitter.isCancelled()) {
                emitter.onNext(state);
                emitted.set(true);
            }
        });
        return current && emitted.get();
    }

    private static void emitStateAndCompleteIfCurrent(
            @NonNull io.reactivex.rxjava3.core.FlowableEmitter<State> emitter,
            @NonNull AttemptLiveness liveness,
            @NonNull State state) {
        liveness.runIfCurrent(() -> {
            if (!emitter.isCancelled()) {
                emitter.onNext(state);
                emitter.onComplete();
            }
        });
    }

    private static void completeIfCurrent(
            @NonNull io.reactivex.rxjava3.core.FlowableEmitter<State> emitter,
            @NonNull AttemptLiveness liveness) {
        liveness.runIfCurrent(() -> {
            if (!emitter.isCancelled()) {
                emitter.onComplete();
            }
        });
    }

    /** Queues a STOMP SEND. False means there is no open socket to accept it. */
    public boolean send(@NonNull String destination, @NonNull String json) {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("destination", destination);
        headers.put("content-type", "application/json");
        return sendFrame(new StompFrame(StompCommand.SEND, headers, json));
    }

    /** Sends SUBSCRIBE and returns MESSAGE bodies for the assigned subscription id. */
    @NonNull
    public Flowable<String> subscribe(@NonNull String destination) {
        String id = subscriptionIds.computeIfAbsent(
                destination,
                ignored -> "sub-" + subscriptionCounter.incrementAndGet());
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("id", id);
        headers.put("destination", destination);
        headers.put("ack", "auto");
        sendFrame(new StompFrame(StompCommand.SUBSCRIBE, headers));
        return getFrames()
                .filter(frame -> StompCommand.MESSAGE.equals(frame.getCommand())
                        && id.equals(frame.getHeaders().get("subscription")))
                .map(StompFrame::getBody);
    }

    /** The current id for a destination, or null when it is not subscribed. */
    @Nullable
    public String subscriptionIdFor(@NonNull String destination) {
        return subscriptionIds.get(destination);
    }

    /** Sends UNSUBSCRIBE and forgets the destination's id. */
    public void unsubscribe(@NonNull String destination) {
        String id = subscriptionIds.remove(destination);
        if (id != null) {
            LinkedHashMap<String, String> headers = new LinkedHashMap<>();
            headers.put("id", id);
            sendFrame(new StompFrame(StompCommand.UNSUBSCRIBE, headers));
        }
    }

    /** Graceful close; sends DISCONNECT first when the STOMP session is live. */
    public void close() {
        WebSocket active = socket;
        if (active != null) {
            if (open) {
                active.send(StompCodec.encode(new StompFrame(StompCommand.DISCONNECT)));
            }
            active.close(NORMAL_CLOSURE_CODE, CLOSE_REASON);
        }
    }

    /** True between CONNECTED and the next close or failure. */
    public boolean isOpen() {
        return open;
    }

    private AutoCloseable startConnection(
            @NonNull String token,
            @NonNull StateSink sink,
            @NonNull LongPredicate stillCurrent,
            long attemptId,
            @NonNull AttemptLiveness liveness
    ) {
        java.util.function.Consumer<String> bearerObserver = connectBearerObserver;
        if (bearerObserver != null) {
            bearerObserver.accept(token);
        }
        StompParser parser = new StompParser();
        Request request = new Request.Builder()
                .url(wsUrl)
                .header("Origin", AppConfig.API_BASE_URL)
                .build();

        WebSocketListener listener = new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                if (!liveness.runIfCurrent(() -> {
                    socket = webSocket;
                    lastInboundMs = System.currentTimeMillis();
                    LinkedHashMap<String, String> headers = new LinkedHashMap<>();
                    headers.put("accept-version", "1.2");
                    headers.put("host", wsUrl.host());
                    headers.put("heart-beat", HEARTBEAT_MS + "," + HEARTBEAT_MS);
                    headers.put("Authorization", "Bearer " + token);
                    webSocket.send(StompCodec.encode(new StompFrame(StompCommand.CONNECT, headers)));
                })) {
                    webSocket.cancel();
                }
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                if (!liveness.runIfCurrent(() -> lastInboundMs = System.currentTimeMillis())) {
                    return;
                }
                for (StompFrame frame : parser.feed(text)) {
                    if (!stillCurrent.test(attemptId)) {
                        return;
                    }
                    String command = frame.getCommand();
                    if (StompCommand.CONNECTED.equals(command)) {
                        if (!liveness.runIfCurrent(() -> {
                            open = true;
                            sink.trySend(State.Open.INSTANCE);
                        })) {
                            return;
                        }
                    } else if (StompCommand.ERROR.equals(command)) {
                        if (!liveness.runIfCurrent(() -> incomingFrames.onNext(frame))) {
                            return;
                        }
                        if (!open) {
                            String reason = isBlank(frame.getBody())
                                    ? REASON_ERROR_FRAME
                                    : frame.getBody();
                            if (!liveness.runIfCurrent(
                                    () -> sink.trySend(new State.Failed(reason)))) {
                                return;
                            }
                        }
                    } else {
                        if (!liveness.runIfCurrent(() -> incomingFrames.onNext(frame))) {
                            return;
                        }
                    }
                }
            }

            @Override
            public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                liveness.runIfCurrent(() -> webSocket.close(code, reason));
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                liveness.runIfCurrent(() -> {
                    open = false;
                    sink.trySend(new State.Closed(code, reason));
                    sink.close();
                });
            }

            @Override
            public void onFailure(
                    @NonNull WebSocket webSocket,
                    @NonNull Throwable failure,
                    @Nullable Response response
            ) {
                liveness.runIfCurrent(() -> {
                    open = false;
                    Integer httpStatus = response == null ? null : response.code();
                    boolean auth = httpStatus != null
                            && (httpStatus == HTTP_UNAUTHORIZED || httpStatus == HTTP_FORBIDDEN);
                    String reason = httpStatus == null ? failure.getMessage() : "HTTP_" + httpStatus;
                    boolean wakeRetryable = httpStatus != null
                            ? httpStatus == 502 || httpStatus == 503 || httpStatus == 504
                            : failure instanceof SocketTimeoutException;
                    sink.trySend(new State.Failed(reason, auth, wakeRetryable));
                    sink.close();
                });
            }
        };

        BiConsumer<WebSocketListener, Long> capture = testListenerCapture;
        if (capture != null) {
            capture.accept(listener, attemptId);
        }
        BiFunction<WebSocketListener, Long, WebSocket> testFactory = testWebSocketFactory;
        WebSocket webSocket = testFactory == null
                ? okHttpClient.newWebSocket(request, listener)
                : testFactory.apply(listener, attemptId);
        if (!liveness.runIfCurrent(() -> socket = webSocket)) {
            webSocket.cancel();
            return () -> { };
        }
        ScheduledExecutorService scheduler = newAttemptScheduler();
        AtomicBoolean cleanedUp = new AtomicBoolean();
        ScheduledFuture<?> connectTimeout = scheduler.schedule(() -> {
            liveness.runIfCurrent(() -> {
                if (!open && !cleanedUp.get()) {
                    sink.trySend(new State.Failed(REASON_CONNECT_TIMEOUT, false, true));
                    webSocket.cancel();
                }
            });
        }, CONNECT_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> heartbeat = scheduler.scheduleWithFixedDelay(() -> {
            liveness.runIfCurrent(() -> {
                if (cleanedUp.get()) {
                    return;
                }
                WebSocket active = socket;
                if (active == null || !open) {
                    return;
                }
                active.send(HEARTBEAT_FRAME);
                if (System.currentTimeMillis() - lastInboundMs > DEAD_AFTER_MS) {
                    active.cancel();
                }
            });
        }, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);

        return () -> {
            if (!cleanedUp.compareAndSet(false, true)) {
                return;
            }
            liveness.invalidate();
            synchronized (attemptSequenceLock) {
                if (connectAttemptSeq.get() == attemptId) {
                    open = false;
                    if (socket == webSocket) {
                        socket = null;
                    }
                }
            }
            connectTimeout.cancel(false);
            heartbeat.cancel(false);
            scheduler.shutdownNow();
            try {
                webSocket.send(StompCodec.encode(new StompFrame(StompCommand.DISCONNECT)));
            } catch (RuntimeException ignored) {
                // The socket may already have been cancelled by its failure callback.
            }
            webSocket.close(NORMAL_CLOSURE_CODE, CLOSE_REASON);
            webSocket.cancel();
        };
    }

    private boolean sendFrame(@NonNull StompFrame frame) {
        WebSocket active = socket;
        return active != null && active.send(StompCodec.encode(frame));
    }

    private static ScheduledExecutorService newAttemptScheduler() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "nexachat-stomp-watchdog");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(factory);
    }

    /** Serializes listener side effects with synchronous attempt cancellation. */
    private static final class AttemptLiveness {
        private final AtomicLong attemptSequence;
        private final long attemptId;
        private final SessionActionGate sessionActionGate;
        private final Object attemptSequenceLock;
        private boolean active = true;

        private AttemptLiveness(
                AtomicLong attemptSequence,
                long attemptId,
                SessionActionGate sessionActionGate,
                Object attemptSequenceLock) {
            this.attemptSequence = attemptSequence;
            this.attemptId = attemptId;
            this.sessionActionGate = sessionActionGate;
            this.attemptSequenceLock = attemptSequenceLock;
        }

        private boolean isCurrent() {
            AtomicBoolean current = new AtomicBoolean();
            boolean sessionCurrent = sessionActionGate.runIfCurrent(() -> {
                synchronized (attemptSequenceLock) {
                    synchronized (this) {
                        current.set(active && attemptSequence.get() == attemptId);
                    }
                }
            });
            return sessionCurrent && current.get();
        }

        private boolean runIfCurrent(Runnable action) {
            AtomicBoolean ran = new AtomicBoolean();
            boolean sessionCurrent = sessionActionGate.runIfCurrent(() -> {
                synchronized (attemptSequenceLock) {
                    synchronized (this) {
                        if (active && attemptSequence.get() == attemptId) {
                            action.run();
                            ran.set(true);
                        }
                    }
                }
            });
            return sessionCurrent && ran.get();
        }

        private synchronized void invalidate() {
            active = false;
        }
    }

    private static boolean isBlank(@NonNull String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (!Character.isWhitespace(current) && !Character.isSpaceChar(current)) {
                return false;
            }
        }
        return true;
    }
}
