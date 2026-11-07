package com.nexachat.core.network.stomp;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

        /** The attempt died before or after Open. {@code auth} marks HTTP 401/403 handshakes. */
        record Failed(@Nullable String reason, boolean auth) implements State {
            public Failed(@Nullable String reason) {
                this(reason, false);
            }

            @Nullable
            public String getReason() {
                return reason;
            }

            public boolean isAuth() {
                return auth;
            }

            @Override
            public String toString() {
                return "Failed(reason=" + reason + ", auth=" + auth + ")";
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

        /** Kotlin-compatible singleton values while retaining the nested state types. */
        Connecting Connecting = StompClient.State.Connecting.INSTANCE;
        Open Open = StompClient.State.Open.INSTANCE;
    }

    /** Java-friendly state emitter used by the Kotlin callbackFlow bridge. */
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

    /** Opens a socket and reports states until it closes, fails, or collection is cancelled. */
    @NonNull
    public Flowable<State> connect() {
        return Flowable.create(emitter -> {
            SerialDisposable lifecycle = new SerialDisposable();
            emitter.setCancellable(lifecycle::dispose);
            emitter.onNext(State.Connecting.INSTANCE);
            SerialDisposable readHolder = new SerialDisposable();
            lifecycle.set(readHolder);
            Disposable read = tokenStore.readTokens()
                    .subscribeOn(Schedulers.io())
                    .doOnComplete(() -> {
                        if (!emitter.isCancelled()) {
                            emitter.onNext(new State.Failed(REASON_NO_TOKEN));
                            emitter.onComplete();
                        }
                    })
                    .subscribe(tokens -> {
                        String token = tokens.getAccessToken();
                        if (token.isBlank()) {
                            emitter.onNext(new State.Failed(REASON_NO_TOKEN));
                            emitter.onComplete();
                            return;
                        }
                        StateSink sink = new StateSink() {
                            @Override public boolean trySend(@NonNull State state) {
                                if (emitter.isCancelled()) return false;
                                emitter.onNext(state);
                                return true;
                            }
                            @Override public void close() {
                                if (!emitter.isCancelled()) emitter.onComplete();
                            }
                        };
                        try {
                            AutoCloseable cleanup = startConnection(token, sink);
                            lifecycle.set(Disposable.fromAction(() -> {
                                try { cleanup.close(); } catch (Exception ignored) { }
                            }));
                        } catch (RuntimeException failure) {
                            emitter.onNext(new State.Failed(failure.getMessage()));
                            emitter.onComplete();
                        }
                    }, failure -> {
                        emitter.onNext(new State.Failed(failure.getMessage()));
                        emitter.onComplete();
                    });
            readHolder.set(read);
        }, BackpressureStrategy.LATEST);
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
            @NonNull StateSink sink
    ) {
        StompParser parser = new StompParser();
        Request request = new Request.Builder()
                .url(wsUrl)
                .header("Origin", AppConfig.API_BASE_URL)
                .build();

        WebSocketListener listener = new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                socket = webSocket;
                lastInboundMs = System.currentTimeMillis();
                LinkedHashMap<String, String> headers = new LinkedHashMap<>();
                headers.put("accept-version", "1.2");
                headers.put("host", wsUrl.host());
                headers.put("heart-beat", HEARTBEAT_MS + "," + HEARTBEAT_MS);
                headers.put("Authorization", "Bearer " + token);
                webSocket.send(StompCodec.encode(new StompFrame(StompCommand.CONNECT, headers)));
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                lastInboundMs = System.currentTimeMillis();
                for (StompFrame frame : parser.feed(text)) {
                    String command = frame.getCommand();
                    if (StompCommand.CONNECTED.equals(command)) {
                        open = true;
                        sink.trySend(State.Open.INSTANCE);
                    } else if (StompCommand.ERROR.equals(command)) {
                        incomingFrames.onNext(frame);
                        if (!open) {
                            String reason = isBlank(frame.getBody())
                                    ? REASON_ERROR_FRAME
                                    : frame.getBody();
                            sink.trySend(new State.Failed(reason));
                        }
                    } else {
                        incomingFrames.onNext(frame);
                    }
                }
            }

            @Override
            public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                webSocket.close(code, reason);
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                open = false;
                sink.trySend(new State.Closed(code, reason));
                sink.close();
            }

            @Override
            public void onFailure(
                    @NonNull WebSocket webSocket,
                    @NonNull Throwable failure,
                    @Nullable Response response
            ) {
                open = false;
                Integer httpStatus = response == null ? null : response.code();
                boolean auth = httpStatus != null
                        && (httpStatus == HTTP_UNAUTHORIZED || httpStatus == HTTP_FORBIDDEN);
                String reason = httpStatus == null ? failure.getMessage() : "HTTP_" + httpStatus;
                sink.trySend(new State.Failed(reason, auth));
                sink.close();
            }
        };

        WebSocket webSocket = okHttpClient.newWebSocket(request, listener);
        socket = webSocket;
        ScheduledExecutorService scheduler = newAttemptScheduler();
        AtomicBoolean cleanedUp = new AtomicBoolean();
        ScheduledFuture<?> connectTimeout = scheduler.schedule(() -> {
            if (!open && !cleanedUp.get()) {
                sink.trySend(new State.Failed(REASON_CONNECT_TIMEOUT));
                webSocket.cancel();
            }
        }, CONNECT_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        ScheduledFuture<?> heartbeat = scheduler.scheduleWithFixedDelay(() -> {
            if (cleanedUp.get()) {
                return;
            }
            WebSocket active = socket;
            if (active == null) {
                return;
            }
            if (!open) {
                return;
            }
            active.send(HEARTBEAT_FRAME);
            if (System.currentTimeMillis() - lastInboundMs > DEAD_AFTER_MS) {
                active.cancel();
            }
        }, HEARTBEAT_MS, HEARTBEAT_MS, TimeUnit.MILLISECONDS);

        return () -> {
            if (!cleanedUp.compareAndSet(false, true)) {
                return;
            }
            open = false;
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
            if (socket == webSocket) {
                socket = null;
            }
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
