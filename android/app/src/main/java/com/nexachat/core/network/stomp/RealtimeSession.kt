package com.nexachat.core.network.stomp

import com.nexachat.core.network.TokenRefresher
import com.nexachat.core.network.dto.MessageDto
import com.nexachat.core.network.dto.PinUpdateDto
import com.nexachat.core.network.dto.ReactionSummaryDto
import com.nexachat.core.network.dto.ReactionUpdateDto
import com.nexachat.core.network.dto.SendMessageRequest
import com.nexachat.core.network.stomp.dto.ReadUpdateDto
import com.nexachat.core.network.stomp.dto.SendErrorDto
import com.nexachat.core.network.stomp.dto.TypingSignalDto
import com.nexachat.core.network.stomp.dto.WsEnvelope
import com.nexachat.core.storage.TokenStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Typed realtime events decoded from the WS envelope (plan §11.2). Event
 * types the client does not consume yet (MESSAGE_UPDATED, presence, …)
 * collapse into [Unknown] — forward-compatible by design.
 */
sealed interface ChatEvent {
    val conversationId: String?

    data class MessageCreated(
        val message: MessageDto,
        override val conversationId: String?,
        val serverTs: String?,
    ) : ChatEvent

    data class UserTyping(
        override val conversationId: String?,
        val userId: String?,
        val displayName: String?,
    ) : ChatEvent

    data class MessageRead(
        override val conversationId: String?,
        val userId: String?,
        val upToSeq: Long?,
    ) : ChatEvent

    data class ConversationUpdated(
        override val conversationId: String?,
    ) : ChatEvent

    /**
     * `REACTION_UPDATED` (phase-10): the message's full summary set —
     * `{messageId, reactions: [{emoji, count, userIds}]}`. Repositories
     * replace the stored set wholesale; an empty [reactions] list clears it.
     */
    data class ReactionUpdated(
        override val conversationId: String?,
        val messageId: String?,
        val reactions: List<ReactionSummaryDto>,
    ) : ChatEvent

    /** `PIN_UPDATED` (phase-10): `{messageId, pinned}` — add/remove flag. */
    data class PinUpdated(
        override val conversationId: String?,
        val messageId: String?,
        val pinned: Boolean?,
    ) : ChatEvent

    /** `/user/queue/errors` — a SEND was rejected server-side. */
    data class SendRejected(
        val code: String?,
        val clientMessageId: String?,
    ) : ChatEvent {
        override val conversationId: String? get() = null
    }

    data class Unknown(
        val eventType: String?,
        override val conversationId: String?,
    ) : ChatEvent
}

/**
 * The WS half of the send/receive pipeline — "WsManager" in plan terms.
 *
 * Lifecycle (plan §12.2): self-starts on first injection (singleton), then
 * shadows [TokenStore.hasValidRefresh] — a token appearing starts the
 * connect/reconnect loop, a logout cancels everything. The reconnect LOOP is
 * owned here (caller-owned per the StompClient contract) and driven by
 * [ReconnectPolicy]; after [ReconnectPolicy.MAX_ATTEMPTS] failures the session
 * reports `Failed(OFFLINE)` and is re-armed by [ensureConnected] from the next
 * user-driven repository call.
 *
 * Fan-in: every frame lands in [events] as a typed [ChatEvent] — repositories
 * filter by conversation, so per-screen subscription bookkeeping is just
 * [watchConversation]/[unwatchConversation] toggling STOMP SUBSCRIBE frames.
 *
 * [resyncRequests] fires on every (re-)Open — "WS is push, REST is source of
 * truth" (plan §11.3.5): repositories answer it with history+inbox refresh
 * and an outbox flush.
 */
@Singleton
class RealtimeSession @Inject constructor(
    private val stompClient: StompClient,
    private val tokenStore: TokenStore,
    private val tokenRefresher: TokenRefresher,
    private val moshi: Moshi,
    private val reconnectPolicy: ReconnectPolicy,
    private val scope: CoroutineScope,
) {

    private val envelopeAdapter = moshi.adapter(WsEnvelope::class.java)
    private val messageAdapter = moshi.adapter(MessageDto::class.java)
    private val sendAdapter = moshi.adapter(SendMessageRequest::class.java)
    private val typingAdapter = moshi.adapter(TypingSignalDto::class.java)
    private val readAdapter = moshi.adapter(ReadUpdateDto::class.java)
    private val sendErrorAdapter = moshi.adapter(SendErrorDto::class.java)
    private val reactionAdapter = moshi.adapter(ReactionUpdateDto::class.java)
    private val pinAdapter = moshi.adapter(PinUpdateDto::class.java)
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
    )

    private val _state = MutableStateFlow<StompClient.State>(
        StompClient.State.Closed(NORMAL_CODE, "never connected"),
    )
    val state: StateFlow<StompClient.State> = _state.asStateFlow()

    private val _isOpen = MutableStateFlow(false)
    val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    private val _events = MutableSharedFlow<ChatEvent>(
        extraBufferCapacity = EVENTS_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<ChatEvent> = _events.asSharedFlow()

    private val _resyncRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = RESYNC_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val resyncRequests: SharedFlow<Unit> = _resyncRequests.asSharedFlow()

    /** Destinations that must be (re-)subscribed on every Open. */
    private val wantedSubscriptions = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var connectionJob: Job? = null

    init {
        wantedSubscriptions += USER_INBOX_DESTINATION
        wantedSubscriptions += USER_ERRORS_DESTINATION
        scope.launch { observeTokens() }
        scope.launch { collectFrames() }
    }

    // ---- connection lifecycle ----

    private suspend fun observeTokens() {
        tokenStore.hasValidRefresh.collect { hasToken ->
            if (hasToken) {
                launchConnectionLoop()
            } else {
                // Logout: stop the loop and hang up so nothing lingers.
                connectionJob?.cancel()
                connectionJob = null
                stompClient.close()
                _isOpen.value = false
            }
        }
    }

    private fun launchConnectionLoop() {
        if (connectionJob?.isActive == true) return
        connectionJob = scope.launch { runConnectionLoop() }
    }

    /** Re-arms the loop after OFFLINE — called by repositories on user actions. */
    fun ensureConnected() = launchConnectionLoop()

    private suspend fun runConnectionLoop() {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            stompClient.connect().collect { state ->
                _state.value = state
                when (state) {
                    is StompClient.State.Open -> {
                        _isOpen.value = true
                        failures = 0
                        resubscribeWanted()
                        _resyncRequests.tryEmit(Unit)
                    }
                    is StompClient.State.Failed -> {
                        _isOpen.value = false
                        if (state.auth) {
                            // 401/403 handshake — rotate the access token once
                            // (mutex-serialized inside TokenRefresher) so the
                            // NEXT attempt authenticates with a fresh token.
                            tokenRefresher.refresh()
                        }
                    }
                    else -> _isOpen.value = false
                }
            }
            _isOpen.value = false
            failures++
            if (!reconnectPolicy.shouldRetry(failures)) {
                _state.value = StompClient.State.Failed(StompClient.REASON_OFFLINE)
                return
            }
            delay(reconnectPolicy.delayMillis(failures))
        }
    }

    private fun resubscribeWanted() {
        for (destination in wantedSubscriptions) {
            stompClient.subscribe(destination)
        }
    }

    // ---- subscriptions on demand ----

    /** Subscribes to `/topic/conversations.{id}`; remembered across reconnects. */
    fun watchConversation(conversationId: String) {
        val destination = topicDestination(conversationId)
        wantedSubscriptions += destination
        if (_isOpen.value) stompClient.subscribe(destination)
    }

    fun unwatchConversation(conversationId: String) {
        val destination = topicDestination(conversationId)
        if (wantedSubscriptions.remove(destination)) {
            stompClient.unsubscribe(destination)
        }
    }

    // ---- sends ----

    /** STOMP SEND to `/app/conversations/{id}/messages`. `false` = not queued. */
    fun sendMessage(conversationId: String, request: SendMessageRequest): Boolean =
        stompClient.send(
            destination = "$APP_PREFIX/conversations/$conversationId/messages",
            json = sendAdapter.toJson(request),
        )

    /** Typing ping — plan §11.2: empty body `{}`, client-throttled upstream. */
    fun sendTyping(conversationId: String): Boolean =
        stompClient.send(
            destination = "$APP_PREFIX/conversations/$conversationId/typing",
            json = "{}",
        )

    // ---- inbound frames ----

    private suspend fun collectFrames() {
        stompClient.frames.collect { frame -> dispatch(frame) }
    }

    private fun dispatch(frame: StompFrame) {
        if (frame.command != StompCommand.MESSAGE) return
        // Error-queue frames are identified by the subscription id we opened,
        // not the delivered destination — the broker rewrites user queues to
        // `/queue/errors-user<sessionId>` (plan §11.2 error path).
        if (frame.headers["subscription"] ==
            stompClient.subscriptionIdFor(USER_ERRORS_DESTINATION)
        ) {
            val error = runCatching { sendErrorAdapter.fromJson(frame.body) }.getOrNull()
            _events.tryEmit(ChatEvent.SendRejected(error?.code, error?.clientMessageId))
            return
        }
        val envelope = runCatching { envelopeAdapter.fromJson(frame.body) }.getOrNull()
            ?: return
        _events.tryEmit(toChatEvent(envelope))
    }

    private fun toChatEvent(envelope: WsEnvelope): ChatEvent = when (envelope.eventType) {
        EVENT_MESSAGE_CREATED -> {
            val message = envelope.payloadJson()
                ?.let { runCatching { messageAdapter.fromJson(it) }.getOrNull() }
            if (message != null) {
                ChatEvent.MessageCreated(
                    message = message,
                    conversationId = envelope.conversationId ?: message.conversationId,
                    serverTs = envelope.serverTs,
                )
            } else {
                ChatEvent.Unknown(envelope.eventType, envelope.conversationId)
            }
        }
        EVENT_USER_TYPING -> {
            val signal = envelope.payloadJson()
                ?.let { runCatching { typingAdapter.fromJson(it) }.getOrNull() }
            ChatEvent.UserTyping(
                conversationId = envelope.conversationId,
                userId = signal?.userId,
                displayName = signal?.displayName,
            )
        }
        EVENT_MESSAGE_READ -> {
            val update = envelope.payloadJson()
                ?.let { runCatching { readAdapter.fromJson(it) }.getOrNull() }
            ChatEvent.MessageRead(
                conversationId = envelope.conversationId,
                userId = update?.resolvedUserId,
                upToSeq = update?.resolvedSeq,
            )
        }
        EVENT_CONVERSATION_UPDATED ->
            ChatEvent.ConversationUpdated(envelope.conversationId)
        EVENT_REACTION_UPDATED -> {
            val update = envelope.payloadJson()
                ?.let { runCatching { reactionAdapter.fromJson(it) }.getOrNull() }
            ChatEvent.ReactionUpdated(
                conversationId = envelope.conversationId,
                messageId = update?.messageId,
                reactions = update?.reactions.orEmpty(),
            )
        }
        EVENT_PIN_UPDATED -> {
            val update = envelope.payloadJson()
                ?.let { runCatching { pinAdapter.fromJson(it) }.getOrNull() }
            ChatEvent.PinUpdated(
                conversationId = envelope.conversationId,
                messageId = update?.messageId,
                pinned = update?.pinned,
            )
        }
        else -> ChatEvent.Unknown(envelope.eventType, envelope.conversationId)
    }

    /**
     * Re-encodes the payload map so the typed adapter sees a real JSON stream
     * (safer than `fromJsonValue`, which is strict about map value types —
     * numbers arrive as Double and would not satisfy `Long` fields).
     */
    private fun WsEnvelope.payloadJson(): String? =
        payload?.let { runCatching { mapAdapter.toJson(it) }.getOrNull() }

    companion object {
        const val APP_PREFIX = "/app"
        const val TOPIC_PREFIX = "/topic/conversations."
        const val USER_INBOX_DESTINATION = "/user/queue/inbox"
        const val USER_ERRORS_DESTINATION = "/user/queue/errors"

        const val EVENT_MESSAGE_CREATED = "MESSAGE_CREATED"
        const val EVENT_USER_TYPING = "USER_TYPING"
        const val EVENT_MESSAGE_READ = "MESSAGE_READ"
        const val EVENT_CONVERSATION_UPDATED = "CONVERSATION_UPDATED"
        const val EVENT_REACTION_UPDATED = "REACTION_UPDATED"
        const val EVENT_PIN_UPDATED = "PIN_UPDATED"

        private const val NORMAL_CODE = 1000
        private const val EVENTS_BUFFER = 128
        private const val RESYNC_BUFFER = 4

        fun topicDestination(conversationId: String): String =
            "$TOPIC_PREFIX$conversationId"
    }
}
