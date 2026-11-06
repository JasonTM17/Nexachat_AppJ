package com.nexachat.core.network.stomp

import com.nexachat.core.common.AppConfig
import com.nexachat.core.storage.TokenStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Raw OkHttp WebSocket + STOMP 1.2 client (plan §11.1 — no SockJS).
 *
 * Endpoint: `{API_BASE_URL}` + path `/ws`. The request keeps the `http(s)`
 * scheme — OkHttp's WebSocket upgrade is driven by the `newWebSocket` call,
 * not a `ws://` URL (OkHttp's HttpUrl rejects `ws`/`wss` outright).
 * Authentication rides inside the STOMP CONNECT frame as
 * `Authorization: Bearer <at>` (the plan rejected query-param tokens because
 * they leak into access logs); the AuthInterceptor additionally stamps the
 * HTTP upgrade request, which is harmless.
 *
 * Design notes:
 *  - [connect] is a cold [Flow]: each collection owns exactly one socket and
 *    cleans it up on cancellation. Callers (RealtimeSession) drive reconnect
 *    themselves — the client deliberately has no hidden reconnect loop so the
 *    "1·2·4·8·16s + jitter, ≤5 tries" policy stays owned in one place. The
 *    policy itself lives in [ReconnectPolicy].
 *  - [frames] exposes every decoded server frame; [subscribe] returns only
 *    the MESSAGE bodies for one destination and is idempotent per destination.
 *  - Heartbeat: a `\n` is written every [HEARTBEAT_MS]; if nothing arrives for
 *    [DEAD_AFTER_MS] the socket is killed so the caller's reconnect kicks in
 *    (plan §11.1: dead-connection detection ~20–30s).
 */
@Singleton
class StompClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val tokenStore: TokenStore,
) {

    /** One connect()-attempt lifecycle. Terminal states are [Failed]/[Closed]. */
    sealed interface State {
        data object Connecting : State

        /** CONNECTED frame received — the STOMP session is live. */
        data object Open : State

        /**
         * The attempt died before or after Open. [auth] is true for HTTP
         * 401/403 handshakes — the caller should rotate the token before retry.
         */
        data class Failed(val reason: String?, val auth: Boolean = false) : State

        /** Remote close handshake observed (code/reason per WS spec). */
        data class Closed(val code: Int, val reason: String) : State
    }

    private val _frames = MutableSharedFlow<StompFrame>(
        extraBufferCapacity = INCOMING_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every decoded server frame (MESSAGE, ERROR, RECEIPT). Never suspends producers. */
    val frames: SharedFlow<StompFrame> = _frames.asSharedFlow()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var open = false

    @Volatile
    private var lastInboundMs = 0L

    private val subscriptionIds = ConcurrentHashMap<String, String>()
    private val subscriptionCounter = AtomicInteger()

    private val wsUrl = wsEndpointUrl(AppConfig.API_BASE_URL)

    /**
     * Opens a socket, sends CONNECT, and reports the attempt lifecycle until
     * the terminal [State.Failed]/[State.Closed] emission. Cancelling the
     * collection closes the socket (DISCONNECT + close handshake).
     */
    fun connect(): Flow<State> = callbackFlow {
        trySend(State.Connecting)

        val token = tokenStore.readTokens()?.accessToken
        if (token.isNullOrBlank()) {
            trySend(State.Failed(REASON_NO_TOKEN))
            close()
            return@callbackFlow
        }

        val parser = StompParser()
        val request = Request.Builder()
            .url(wsUrl)
            .header("Origin", AppConfig.API_BASE_URL)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                lastInboundMs = System.currentTimeMillis()
                val connectFrame = StompFrame(
                    StompCommand.CONNECT,
                    linkedMapOf(
                        "accept-version" to "1.2",
                        "host" to wsUrl.host,
                        "heart-beat" to "$HEARTBEAT_MS,$HEARTBEAT_MS",
                        "Authorization" to "Bearer $token",
                    ),
                )
                webSocket.send(StompCodec.encode(connectFrame))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastInboundMs = System.currentTimeMillis()
                for (frame in parser.feed(text)) {
                    when (frame.command) {
                        StompCommand.CONNECTED -> {
                            open = true
                            trySend(State.Open)
                        }
                        StompCommand.ERROR -> {
                            _frames.tryEmit(frame)
                            if (!open) {
                                trySend(State.Failed(frame.body.ifBlank { REASON_ERROR_FRAME }))
                            }
                        }
                        else -> _frames.tryEmit(frame)
                    }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                open = false
                trySend(State.Closed(code, reason))
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                open = false
                val httpStatus = response?.code
                val auth = httpStatus == HTTP_UNAUTHORIZED || httpStatus == HTTP_FORBIDDEN
                trySend(
                    State.Failed(
                        reason = httpStatus?.let { "HTTP_$it" } ?: t.message,
                        auth = auth,
                    ),
                )
                close()
            }
        }

        val webSocket = okHttpClient.newWebSocket(request, listener)
        socket = webSocket

        // CONNECT acknowledgement watchdog: a server that accepts the socket
        // but never answers CONNECT leaves the attempt half-open forever.
        launch {
            delay(CONNECT_ACK_TIMEOUT_MS)
            if (!open && isActive) {
                trySend(State.Failed(REASON_CONNECT_TIMEOUT))
                webSocket.cancel()
            }
        }

        // Heartbeat writer + dead-socket detector (plan §11.1 heart-beat:10000,10000).
        launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                val active = socket ?: break
                if (!open) continue
                active.send(HEARTBEAT_FRAME)
                if (System.currentTimeMillis() - lastInboundMs > DEAD_AFTER_MS) {
                    // Peer stopped answering ~3 heartbeat intervals — kill the
                    // socket so onFailure fires and the caller reconnects.
                    active.cancel()
                    break
                }
            }
        }

        awaitClose {
            open = false
            socket?.let { active ->
                runCatching {
                    active.send(StompCodec.encode(StompFrame(StompCommand.DISCONNECT)))
                }
                active.close(NORMAL_CLOSURE_CODE, CLOSE_REASON)
                active.cancel()
            }
            socket = null
        }
    }

    /**
     * Queues a STOMP SEND toward [destination] with a JSON body. `false` when
     * no open socket could take it — the outbox keeps the row and retries, so
     * dropping here is safe-by-design, not a data loss.
     */
    fun send(destination: String, json: String): Boolean = sendFrame(
        StompFrame(
            StompCommand.SEND,
            linkedMapOf(
                "destination" to destination,
                "content-type" to "application/json",
            ),
            json,
        ),
    )

    /**
     * Sends SUBSCRIBE for [destination] (idempotent — the same subscription id
     * is reused for repeat calls) and returns the MESSAGE bodies of that
     * subscription. The send is fire-and-forget: when the socket is down the
     * frame is dropped and RealtimeSession re-subscribes on the next Open.
     */
    fun subscribe(destination: String): Flow<String> {
        val id = subscriptionIds.getOrPut(destination) {
            "sub-${subscriptionCounter.incrementAndGet()}"
        }
        sendFrame(
            StompFrame(
                StompCommand.SUBSCRIBE,
                linkedMapOf(
                    "id" to id,
                    "destination" to destination,
                    "ack" to "auto",
                ),
            ),
        )
        return frames
            .filter { it.command == StompCommand.MESSAGE && it.headers["subscription"] == id }
            .map { it.body }
    }

    /**
     * The subscription id currently assigned to [destination], or null if not
     * subscribed. Inbound MESSAGE frames carry this id in their `subscription`
     * header — match on it rather than the `destination` header, because the
     * server rewrites user destinations (e.g. `/user/queue/errors` arrives as
     * `/queue/errors-user<sessionId>`).
     */
    fun subscriptionIdFor(destination: String): String? = subscriptionIds[destination]

    /** Sends UNSUBSCRIBE for [destination] and forgets its subscription id. */
    fun unsubscribe(destination: String) {
        subscriptionIds.remove(destination)?.let { id ->
            sendFrame(
                StompFrame(
                    StompCommand.UNSUBSCRIBE,
                    linkedMapOf("id" to id),
                ),
            )
        }
    }

    /** Graceful close (sends DISCONNECT first when the socket is live). */
    fun close() {
        socket?.let { active ->
            if (open) {
                runCatching {
                    active.send(StompCodec.encode(StompFrame(StompCommand.DISCONNECT)))
                }
            }
            active.close(NORMAL_CLOSURE_CODE, CLOSE_REASON)
        }
    }

    /** True between CONNECTED and the next close/failure. */
    fun isOpen(): Boolean = open

    private fun sendFrame(frame: StompFrame): Boolean =
        socket?.send(StompCodec.encode(frame)) == true

    companion object {
        const val HEARTBEAT_MS = 10_000L
        const val DEAD_AFTER_MS = 30_000L
        const val CONNECT_ACK_TIMEOUT_MS = 8_000L
        const val NORMAL_CLOSURE_CODE = 1000
        const val CLOSE_REASON = "client closing"
        const val REASON_NO_TOKEN = "NO_TOKEN"
        const val REASON_ERROR_FRAME = "ERROR_FRAME"
        const val REASON_CONNECT_TIMEOUT = "CONNECT_TIMEOUT"
        const val REASON_OFFLINE = "OFFLINE"
        private const val HEARTBEAT_FRAME = "\n"
        private const val INCOMING_BUFFER = 256
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
    }
}

/**
 * Builds the WebSocket upgrade URL for [baseUrl] (`{base}/ws`). The scheme is
 * left as `http`/`https` on purpose: OkHttp's `HttpUrl` rejects `ws`/`wss`,
 * and [OkHttpClient.newWebSocket] performs the upgrade on a plain request.
 * `https` transparently means `wss` (TLS) during the upgrade.
 */
internal fun wsEndpointUrl(baseUrl: String): HttpUrl =
    baseUrl.toHttpUrl()
        .newBuilder()
        .addPathSegment("ws")
        .build()
