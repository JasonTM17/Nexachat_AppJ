package com.nexachat.data

import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.database.ConversationDao
import com.nexachat.core.database.MessageDao
import com.nexachat.core.database.MessageEntity
import com.nexachat.core.database.MessageReactionEntity
import com.nexachat.core.database.OutboxDao
import com.nexachat.core.database.OutboxEntity
import com.nexachat.core.database.OutboxState
import com.nexachat.core.database.PinDao
import com.nexachat.core.database.PinnedMessageEntity
import com.nexachat.core.database.ReactionDao
import com.nexachat.core.network.ApiResult
import com.nexachat.core.network.MessageApi
import com.nexachat.core.network.SessionEvents
import com.nexachat.core.network.dto.AttachmentDto
import com.nexachat.core.network.dto.MessageDto
import com.nexachat.core.network.dto.MessagePageDto
import com.nexachat.core.network.dto.PinRequest
import com.nexachat.core.network.dto.ReactionRequest
import com.nexachat.core.network.dto.ReactionSummaryDto
import com.nexachat.core.network.dto.ReadRequest
import com.nexachat.core.network.dto.SendMessageRequest
import com.nexachat.core.network.safeApiCall
import com.nexachat.core.network.stomp.ChatEvent
import com.nexachat.core.network.stomp.RealtimeSession
import com.nexachat.core.storage.TokenStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Delivery state the chat UI renders per bubble. */
enum class DeliveryStatus { SENT, SENDING, FAILED }

/**
 * Reaction chip under a bubble — aggregated from `message_reactions`
 * ([reactedByMe] resolves `reactedByMe ?: userIds.contains(myId)` at ingest).
 */
data class ReactionChip(
    val emoji: String,
    val count: Int,
    val reactedByMe: Boolean,
)

/**
 * Message as the feature layer sees it — merges the Room row (SENT) and the
 * outbox row (SENDING/FAILED) into one shape, [mine] already resolved.
 */
data class ChatMessage(
    /** Server message id, or the clientMessageId while still pending. */
    val id: String,
    val conversationId: String,
    val seq: Long?,
    val senderId: String,
    val senderDisplayName: String?,
    val type: String,
    val content: String?,
    val replyToId: String?,
    /** Embedded `replyTo` preview columns — survive the original being unloaded. */
    val replyToSenderName: String?,
    val replyToSnippet: String?,
    val createdAtEpochMs: Long,
    val clientMessageId: String?,
    val status: DeliveryStatus,
    val mine: Boolean,
    /** Parsed `attachmentsJson` — empty for TEXT-only bubbles. */
    val attachments: List<AttachmentDto> = emptyList(),
    /** Aggregated emoji chips rendered under the bubble. */
    val reactions: List<ReactionChip> = emptyList(),
)

/** Peer-is-typing signal forwarded to the open chat screen (5s TTL applied there). */
data class TypingEvent(
    val conversationId: String,
    val userId: String?,
    val displayName: String?,
)

/** Read-watermark event for the open conversation (read receipts, P2). */
data class ReadEvent(
    val conversationId: String,
    val userId: String?,
    val upToSeq: Long?,
)

/**
 * The message pipeline (plan §12.2):
 *
 *   send() → OutboxEntity(SENDING) → STOMP SEND → ack by MESSAGE_CREATED with
 *   the same clientMessageId → row deleted + real message upserted (SENT).
 *   WS down → REST POST fallback → success acks identically; a server
 *   rejection (`/user/queue/errors` or HTTP error) flips the row to FAILED —
 *   tap-to-retry requeues it. Pure network failure keeps SENDING; the flush
 *   on reconnect ([resyncRequests] / [flushOutbox]) retries in order.
 *
 * Room is the only render source — WS events are written through, never
 * rendered directly — so dedup by message id (IGNORE) and clientMessageId is
 * the load-bearing rule of §11.3.3.
 */
@Singleton
class MessageRepository @Inject constructor(
    private val messageApi: MessageApi,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val outboxDao: OutboxDao,
    private val reactionDao: ReactionDao,
    private val pinDao: PinDao,
    private val session: RealtimeSession,
    private val tokenStore: TokenStore,
    private val sessionEvents: SessionEvents,
    private val moshi: Moshi,
    private val scope: CoroutineScope,
) {

    /** Conversation the user is looking at — suppresses its unread bumps. */
    private val activeConversation = MutableStateFlow<String?>(null)

    /** Server-confirmed read watermark per conversation — dedups POST /read. */
    private val lastMarkedRead = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val _typingEvents = MutableSharedFlow<TypingEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val typingEvents: SharedFlow<TypingEvent> = _typingEvents.asSharedFlow()

    private val _readEvents = MutableSharedFlow<ReadEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val readEvents: SharedFlow<ReadEvent> = _readEvents.asSharedFlow()

    /**
     * Reaction/pin action failures (403, PIN_LIMIT, offline) surfaced for a
     * snackbar — same pattern as [typingEvents]; Room writes already reverted.
     */
    private val _interactionEvents = MutableSharedFlow<ApiError>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val interactionEvents: SharedFlow<ApiError> = _interactionEvents.asSharedFlow()

    private val attachmentsAdapter = moshi.adapter<List<AttachmentDto>>(
        Types.newParameterizedType(List::class.java, AttachmentDto::class.java),
    )

    /** Loose JSON → Map/List/String for the pins endpoint's variant shapes. */
    private val anyAdapter = moshi.adapter(Any::class.java)

    /** Embedded `message` objects inside `GET /pins` entries. */
    private val messageAdapter = moshi.adapter(MessageDto::class.java)

    @Volatile
    private var tokenToUser: Pair<String, String>? = null

    init {
        scope.launch { routeSessionEvents() }
        scope.launch { resyncOnReconnect() }
        scope.launch { wipeOnLogout() }
    }

    // ---- read model ----

    /**
     * Room messages merged with this conversation's pending outbox rows.
     * Sent messages order by `seq`; pending ones trail by client `createdAt`
     * (their seq does not exist yet — a retried FAILED message re-sorts to the
     * bottom, matching mainstream messenger behaviour).
     */
    fun messagesFlow(conversationId: String): Flow<List<ChatMessage>> = combine(
        messageDao.messagesForConversation(conversationId),
        outboxDao.pendingOrdered(),
        reactionDao.reactionsForConversation(conversationId),
    ) { messages, pending, reactions ->
        val myId = currentUserId()
        val chips = reactions.groupBy({ it.messageId }) { it.toChip() }
        val sent = messages.map { it.toChatMessage(myId, chips[it.id].orEmpty()) }
        val queued = pending
            .filter { it.conversationId == conversationId }
            .map { it.toChatMessage() }
        sent + queued
    }

    /**
     * Pinned messages for the banner — pin rows resolved against stored
     * messages, newest pin first. Messages not yet in local history still
     * appear (id-only stub) so the banner count matches the server.
     */
    fun pinsFlow(conversationId: String): Flow<List<ChatMessage>> = combine(
        pinDao.pinnedFor(conversationId),
        messageDao.messagesForConversation(conversationId),
    ) { pins, messages ->
        val myId = currentUserId()
        val byId = messages.associateBy { it.id }
        pins.map { pin ->
            byId[pin.messageId]?.toChatMessage(myId)
                ?: ChatMessage(
                    id = pin.messageId,
                    conversationId = pin.conversationId,
                    seq = null,
                    senderId = "",
                    senderDisplayName = null,
                    type = MessageDto.TYPE_TEXT,
                    content = null,
                    replyToId = null,
                    replyToSenderName = null,
                    replyToSnippet = null,
                    createdAtEpochMs = pin.pinnedAt,
                    clientMessageId = null,
                    status = DeliveryStatus.SENT,
                    mine = false,
                )
        }
    }

    // ---- conversation lifecycle ----

    /**
     * Marks [conversationId] as the visible one: watches its topic, marks
     * stored history read, and kicks the outbox. History paging is separate —
     * callers pull [refreshHistory] so they can capture `hasMore`.
     */
    suspend fun openConversation(conversationId: String) {
        activeConversation.value = conversationId
        session.ensureConnected()
        session.watchConversation(conversationId)
        sendRead(conversationId, messageDao.maxSeq(conversationId))
        flushOutbox()
        refreshPins(conversationId)
    }

    fun closeConversation(conversationId: String) {
        if (activeConversation.value == conversationId) {
            activeConversation.value = null
        }
        session.unwatchConversation(conversationId)
    }

    // ---- history ----

    /** Fetches one history page and writes it through to Room. */
    suspend fun refreshHistory(
        conversationId: String,
        beforeSeq: Long? = null,
    ): Result<MessagePageDto> =
        when (val result = call { messageApi.getMessages(conversationId, beforeSeq) }) {
            is Result.Success -> {
                storeHistoryItems(conversationId, result.value.items)
                Result.Success(result.value)
            }
            is Result.Failure -> result
        }

    /**
     * Write-through shared by history paging and reconnect resync: message
     * rows upsert by id, and per-message `reactions` summaries replace
     * wholesale — but ONLY for messages that actually carried the field
     * (`null` = absent, must not wipe local state).
     */
    private suspend fun storeHistoryItems(conversationId: String, items: List<MessageDto>) {
        messageDao.upsertAll(items.mapNotNull { it.toEntity(conversationId) })
        val myId = currentUserId()
        val reactionRows = mutableListOf<MessageReactionEntity>()
        val reactionOwnerIds = mutableListOf<String>()
        for (dto in items) {
            val summaries = dto.reactions ?: continue
            // Same resolution as MessageEntity.toEntity — the row's
            // conversationId must match the message's or the flow misses it.
            val resolvedConversationId = dto.conversationId ?: conversationId
            reactionOwnerIds += dto.id
            summaries.mapNotNullTo(reactionRows) {
                it.toEntity(dto.id, resolvedConversationId, myId)
            }
        }
        reactionDao.replaceForMessages(reactionOwnerIds, reactionRows)
    }

    // ---- send pipeline ----

    /**
     * Optimistic send (FR-C5): the outbox row appears in [messagesFlow]
     * immediately as SENDING; delivery resolves asynchronously.
     *
     * [type]/[attachments] carry the media send (phase-09): the dto list is
     * persisted on the outbox row so retries after restart still re-derive the
     * `attachmentIds` wire field, and the pending bubble renders the same
     * attachment model a SENT row would.
     */
    suspend fun send(
        conversationId: String,
        content: String,
        replyToId: String? = null,
        type: String = MessageDto.TYPE_TEXT,
        attachments: List<AttachmentDto> = emptyList(),
    ) {
        val entry = OutboxEntity(
            clientMessageId = UUID.randomUUID().toString(),
            conversationId = conversationId,
            type = type,
            content = content,
            replyToId = replyToId,
            state = OutboxState.SENDING,
            createdAt = System.currentTimeMillis(),
            attachmentsJson = attachments
                .takeIf { it.isNotEmpty() }
                ?.let { runCatching { attachmentsAdapter.toJson(it) }.getOrNull() }
                .orEmpty(),
        )
        outboxDao.insert(entry)
        dispatchOutbox(entry)
    }

    /** Tap-to-retry on a FAILED bubble: requeue and dispatch again. */
    suspend fun retry(clientMessageId: String) {
        outboxDao.updateState(clientMessageId, OutboxState.SENDING)
        outboxDao.byId(clientMessageId)?.let { dispatchOutbox(it) }
    }

    /** Flushes every SENDING row in queue order — called on (re)connect. */
    suspend fun flushOutbox() {
        for (entry in outboxDao.pendingList()) {
            dispatchOutbox(entry)
        }
    }

    private suspend fun dispatchOutbox(entry: OutboxEntity) {
        val request = SendMessageRequest(
            clientMessageId = entry.clientMessageId,
            type = entry.type,
            content = entry.content,
            replyToId = entry.replyToId,
            // WS SEND and the REST fallback both carry the same ids (plan §11.2).
            attachmentIds = entry.attachments().mapNotNull { it.id },
        )
        if (session.sendMessage(entry.conversationId, request)) {
            return // ack arrives as MESSAGE_CREATED → onMessageCreated
        }
        if (!session.isOpen.value) {
            // WS down → REST fallback (plan §12.2). An HTTP error is a real
            // answer (NOT_MEMBER, validation) → FAILED; a transport failure is
            // not → stay SENDING for the next flush.
            when (val result = call { messageApi.sendMessage(entry.conversationId, request) }) {
                is Result.Success -> acknowledge(entry.conversationId, result.value)
                is Result.Failure -> if (result.error !is ApiError.Network) {
                    outboxDao.updateState(entry.clientMessageId, OutboxState.FAILED)
                }
            }
        }
        // else: WS open but the queue refused (closing/full) — stays SENDING,
        // flushed by the next dispatch trigger.
    }

    /** Server-acked delivery shared by the REST fallback and WS dedup path. */
    private suspend fun acknowledge(conversationId: String, message: MessageDto) {
        val entity = message.toEntity(conversationId) ?: return
        entity.clientMessageId?.let { outboxDao.delete(it) }
        messageDao.upsert(entity)
        updateInboxProjection(entity)
    }

    // ---- read watermark ----

    /** POST /read + local unread clear; deduped per watermark (never regresses). */
    suspend fun sendRead(conversationId: String, upToSeq: Long) {
        if (upToSeq <= 0) return
        val last = lastMarkedRead[conversationId] ?: 0L
        if (upToSeq <= last) return
        lastMarkedRead[conversationId] = upToSeq
        when (call { messageApi.markRead(conversationId, ReadRequest(upToSeq)) }) {
            is Result.Success -> conversationDao.clearUnread(conversationId)
            is Result.Failure -> Unit // best-effort — next open/message retries
        }
    }

    // ---- WS event intake ----

    private suspend fun routeSessionEvents() {
        session.events.collect { event ->
            when (event) {
                is ChatEvent.MessageCreated -> onMessageCreated(event)
                is ChatEvent.UserTyping -> event.conversationId?.let { convId ->
                    _typingEvents.tryEmit(TypingEvent(convId, event.userId, event.displayName))
                }
                is ChatEvent.MessageRead -> event.conversationId?.let { convId ->
                    _readEvents.tryEmit(ReadEvent(convId, event.userId, event.upToSeq))
                }
                is ChatEvent.SendRejected -> event.clientMessageId?.let { clientId ->
                    outboxDao.updateState(clientId, OutboxState.FAILED)
                }
                is ChatEvent.ReactionUpdated -> onReactionUpdated(event)
                is ChatEvent.PinUpdated -> onPinUpdated(event)
                else -> Unit
            }
        }
    }

    private suspend fun onMessageCreated(event: ChatEvent.MessageCreated) {
        val dto = event.message
        val conversationId = dto.conversationId ?: event.conversationId ?: return
        val entity = dto.toEntity(conversationId) ?: return
        // Sender dedup: our own optimistic row coming back. Only delete the
        // pending row when the broadcast is actually ours — a foreign sender
        // reusing the clientMessageId must not clear our outbox entry.
        entity.clientMessageId?.let { cid ->
            if (isMine(entity.senderId)) outboxDao.delete(cid)
        }
        // Receiver dedup: INSERT IGNORE by message id (plan §11.3.3).
        messageDao.upsert(entity)
        updateInboxProjection(entity)
    }

    private suspend fun updateInboxProjection(entity: MessageEntity) {
        val mine = isMine(entity.senderId)
        val openHere = activeConversation.value == entity.conversationId
        conversationDao.updateLastMessage(
            conversationId = entity.conversationId,
            seq = entity.seq,
            content = entity.content ?: entity.type,
            senderName = entity.senderDisplayName,
            at = entity.createdAt,
        )
        if (!mine && !openHere) {
            conversationDao.incrementUnread(entity.conversationId, 1)
        }
        if (!mine && openHere) {
            // Already looking at it — keep the watermark trailing live.
            sendRead(entity.conversationId, entity.seq)
        }
    }

    private suspend fun resyncOnReconnect() {
        session.resyncRequests.collect {
            flushOutbox()
            activeConversation.value?.let { conversationId ->
                resyncForward(conversationId)
                sendRead(conversationId, messageDao.maxSeq(conversationId))
                refreshPins(conversationId)
            }
        }
    }

    /**
     * `REACTION_UPDATED` → wholesale summary replace for the message. The
     * event may land before the message itself (out-of-order fan-out) — the
     * rows are keyed by messageId and join at render, so order doesn't matter.
     */
    private suspend fun onReactionUpdated(event: ChatEvent.ReactionUpdated) {
        val messageId = event.messageId ?: return
        val conversationId = event.conversationId ?: return
        val myId = currentUserId()
        reactionDao.replaceForMessage(
            messageId,
            event.reactions.mapNotNull { it.toEntity(messageId, conversationId, myId) },
        )
    }

    /** `PIN_UPDATED` → upsert on `pinned=true`, delete on `pinned=false`. */
    private suspend fun onPinUpdated(event: ChatEvent.PinUpdated) {
        val messageId = event.messageId ?: return
        val conversationId = event.conversationId ?: return
        when (event.pinned) {
            true -> pinDao.upsert(
                PinnedMessageEntity(
                    conversationId = conversationId,
                    messageId = messageId,
                    pinnedAt = System.currentTimeMillis(),
                ),
            )
            false -> pinDao.delete(conversationId, messageId)
            null -> Unit
        }
    }

    // ---- reactions ----

    /**
     * Toggle [emoji] on [messageId]: optimistic Room write first so the chip
     * flips instantly, then the REST toggle — the response (or the
     * REACTION_UPDATED broadcast) reconciles. Any failure restores the exact
     * prior snapshot and surfaces on [interactionEvents].
     */
    suspend fun toggleReaction(messageId: String, conversationId: String, emoji: String) {
        val before = reactionDao.forMessage(messageId)
        val optimistic = toggledCopy(before, messageId, conversationId, emoji)
        reactionDao.replaceForMessage(messageId, optimistic)
        when (
            val result = call { messageApi.toggleReaction(messageId, ReactionRequest(emoji)) }
        ) {
            is Result.Success -> {
                val myId = currentUserId()
                // 204 (the shipped server contract) reaches here as a `Unit`
                // body cast to the DTO type — dereferencing `.reactions`
                // throws ClassCastException, so read it inside runCatching.
                // Absent body leaves the optimistic write; the broadcast
                // reconciles.
                runCatching { result.value.reactions }.getOrNull()
                    ?.takeIf { it.isNotEmpty() }?.let { summaries ->
                        reactionDao.replaceForMessage(
                            messageId,
                            summaries.mapNotNull { it.toEntity(messageId, conversationId, myId) },
                        )
                    }
            }
            is Result.Failure -> {
                // Restore only if nothing fresher landed during the flight —
                // a REACTION_UPDATED from another member wins over the
                // stale snapshot.
                if (reactionDao.forMessage(messageId) == optimistic) {
                    reactionDao.replaceForMessage(messageId, before)
                }
                _interactionEvents.tryEmit(result.error)
            }
        }
    }

    /** Optimistic set-copy of [before] with [emoji] flipped for me. */
    private fun toggledCopy(
        before: List<MessageReactionEntity>,
        messageId: String,
        conversationId: String,
        emoji: String,
    ): List<MessageReactionEntity> {
        val existing = before.firstOrNull { it.emoji == emoji }
        return if (existing?.reactedByMe == true) {
            val remaining = existing.count - 1
            if (remaining <= 0) {
                before - existing
            } else {
                before.map {
                    if (it.emoji == emoji) it.copy(count = remaining, reactedByMe = false) else it
                }
            }
        } else {
            val updated = existing?.copy(count = existing.count + 1, reactedByMe = true)
                ?: MessageReactionEntity(
                    messageId = messageId,
                    conversationId = conversationId,
                    emoji = emoji,
                    count = 1,
                    reactedByMe = true,
                )
            before.filterNot { it.emoji == emoji } + updated
        }
    }

    // ---- pins ----

    /**
     * `GET /pins` write-through — the resync source of truth between
     * PIN_UPDATED pushes. Tolerant of every documented response spelling.
     */
    suspend fun refreshPins(conversationId: String): Result<Unit> =
        when (val result = call { messageApi.getPins(conversationId) }) {
            is Result.Success -> {
                val body = runCatching { result.value.string() }.getOrNull()
                    ?: return Result.Failure(ApiError.Network("body read failed"))
                val (pins, embedded) = parsePinsBody(conversationId, body)
                // Pinned messages outside the loaded window still render:
                // the server embeds full message rows (PinnedMessageView),
                // so persist them before the banner resolves.
                if (embedded.isNotEmpty()) {
                    messageDao.upsertAll(embedded.mapNotNull { it.toEntity(conversationId) })
                }
                pinDao.replaceForConversation(conversationId, pins)
                Result.Success(Unit)
            }
            is Result.Failure -> result
        }

    /** Optimistic pin — revert + signal on failure (403 non-admin, PIN_LIMIT). */
    suspend fun pinMessage(conversationId: String, messageId: String) {
        val entry = PinnedMessageEntity(
            conversationId = conversationId,
            messageId = messageId,
            pinnedAt = System.currentTimeMillis(),
        )
        pinDao.upsert(entry)
        when (val result = call { messageApi.pinMessage(conversationId, PinRequest(messageId)) }) {
            is Result.Success -> Unit
            is Result.Failure -> {
                // Remove our optimistic row only if it is still ours — a
                // PIN_UPDATED or refresh landing in flight wins over the
                // stale delete.
                if (pinDao.find(conversationId, messageId) == entry) {
                    pinDao.delete(conversationId, messageId)
                }
                _interactionEvents.tryEmit(result.error)
            }
        }
    }

    /** Optimistic unpin — restore the removed row + signal on failure. */
    suspend fun unpinMessage(conversationId: String, messageId: String) {
        val before = pinDao.find(conversationId, messageId)
        pinDao.delete(conversationId, messageId)
        when (val result = call { messageApi.unpinMessage(conversationId, messageId) }) {
            is Result.Success -> Unit
            is Result.Failure -> {
                // Restore only while the row is still absent — fresher
                // server state wins over the stale snapshot.
                if (pinDao.find(conversationId, messageId) == null) {
                    before?.let { pinDao.upsert(it) }
                }
                _interactionEvents.tryEmit(result.error)
            }
        }
    }

    /**
     * Reconnect resync (plan §11.3.5): page forward from the local watermark
     * via `afterSeq` until contiguous, so a >1-page gap can never leave a
     * permanent hole in Room. Cold conversations fall back to the newest page.
     */
    suspend fun resyncForward(conversationId: String) {
        var afterSeq = messageDao.maxSeq(conversationId)
        if (afterSeq <= 0L) {
            refreshHistory(conversationId)
            return
        }
        while (true) {
            val result = call { messageApi.getMessages(conversationId, afterSeq = afterSeq) }
            val page = (result as? Result.Success)?.value ?: return
            storeHistoryItems(conversationId, page.items)
            val entities = page.items.mapNotNull { it.toEntity(conversationId) }
            val lastSeq = entities.maxOfOrNull { it.seq } ?: return
            if (!page.hasMore || lastSeq <= afterSeq) return
            afterSeq = lastSeq
        }
    }

    /** Session-dead ⇒ local copies of server data go with it. */
    private suspend fun wipeOnLogout() {
        sessionEvents.events.collect { event ->
            if (event is SessionEvents.Event.LoggedOut) {
                messageDao.deleteAll()
                conversationDao.clear()
                outboxDao.clear()
                reactionDao.clear()
                pinDao.clear()
                lastMarkedRead.clear()
                activeConversation.value = null
                tokenToUser = null
            }
        }
    }

    // ---- identity ----

    /** Our user id, decoded from the access JWT `sub` claim (memoized per token). */
    suspend fun currentUserId(): String? {
        val token = tokenStore.readTokens()?.accessToken ?: return null
        tokenToUser?.let { if (it.first == token) return it.second }
        val userId = userIdFromJwt(token, moshi)
        if (userId != null) tokenToUser = token to userId
        return userId
    }

    suspend fun isMine(senderId: String?): Boolean =
        senderId != null && senderId == currentUserId()

    // ---- mappers ----

    private fun MessageEntity.toChatMessage(
        myId: String?,
        reactions: List<ReactionChip> = emptyList(),
    ): ChatMessage = ChatMessage(
        id = id,
        conversationId = conversationId,
        seq = seq,
        senderId = senderId,
        senderDisplayName = senderDisplayName,
        type = type,
        content = content,
        replyToId = replyToId,
        replyToSenderName = replyToSenderName,
        replyToSnippet = replyToSnippet,
        createdAtEpochMs = createdAt,
        clientMessageId = clientMessageId,
        status = DeliveryStatus.SENT,
        mine = senderId == myId,
        attachments = attachments(),
        reactions = reactions,
    )

    private fun OutboxEntity.toChatMessage(): ChatMessage = ChatMessage(
        id = clientMessageId,
        conversationId = conversationId,
        seq = null,
        senderId = "",
        senderDisplayName = null,
        type = type,
        content = content,
        replyToId = replyToId,
        replyToSenderName = null,
        replyToSnippet = null,
        createdAtEpochMs = createdAt,
        clientMessageId = clientMessageId,
        status = if (state == OutboxState.FAILED) DeliveryStatus.FAILED else DeliveryStatus.SENDING,
        mine = true,
        attachments = attachments(),
    )

    private fun MessageDto.toEntity(conversationIdFallback: String?): MessageEntity? {
        val conversation = conversationId ?: conversationIdFallback ?: return null
        return MessageEntity(
            id = id,
            conversationId = conversation,
            seq = seq ?: 0L,
            senderId = resolvedSenderId.orEmpty(),
            senderDisplayName = resolvedSenderName,
            type = resolvedType,
            content = content,
            clientMessageId = clientMessageId,
            replyToId = resolvedReplyToId,
            replyToSenderName = replyTo?.resolvedSenderName,
            replyToSnippet = replyTo?.resolvedSnippet,
            createdAt = isoToEpochMs(createdAt),
            attachmentsJson = attachments
                .takeIf { it.isNotEmpty() }
                ?.let { runCatching { attachmentsAdapter.toJson(it) }.getOrNull() },
        )
    }

    /** `attachmentsJson` → dto list on both entities; null/blank/corrupt → empty. */
    private fun MessageEntity.attachments(): List<AttachmentDto> =
        attachmentsJson?.let { runCatching { attachmentsAdapter.fromJson(it) }.getOrNull() }
            .orEmpty()

    private fun OutboxEntity.attachments(): List<AttachmentDto> =
        attachmentsJson.takeIf { it.isNotBlank() }
            ?.let { runCatching { attachmentsAdapter.fromJson(it) }.getOrNull() }
            .orEmpty()

    /**
     * `REACTION_UPDATED`/`POST /reactions` summary → Room row. `reactedByMe`
     * resolves the explicit REST field first, else falls back to the WS
     * `userIds` list; summaries with no usable emoji are dropped.
     */
    private fun ReactionSummaryDto.toEntity(
        messageId: String,
        conversationId: String,
        myId: String?,
    ): MessageReactionEntity? {
        val usableEmoji = emoji?.takeIf { it.isNotBlank() } ?: return null
        return MessageReactionEntity(
            messageId = messageId,
            conversationId = conversationId,
            emoji = usableEmoji,
            count = (count ?: userIds.size).coerceAtLeast(0),
            reactedByMe = reactedByMe ?: byMe ?: (myId != null && userIds.contains(myId)),
        )
    }

    private fun MessageReactionEntity.toChip(): ReactionChip =
        ReactionChip(emoji = emoji, count = count, reactedByMe = reactedByMe)

    // ---- pins body parsing ----

    /**
     * `GET /pins` tolerant parse — every documented spelling reduces to a
     * pin row: `["m-1"]`, `[{messageId}]`, `[{id}]`, `[{message:{id}}]`, or an
     * envelope (`items`/`pins`/`messageIds`). Wire order is preserved by
     * decreasing `pinnedAt` so the banner's "latest" slot is deterministic.
     */
    private fun parsePinsBody(
        conversationId: String,
        body: String,
    ): Pair<List<PinnedMessageEntity>, List<MessageDto>> {
        val parsed = runCatching { anyAdapter.fromJson(body) }.getOrNull()
            ?: return emptyList<PinnedMessageEntity>() to emptyList()
        val entries: List<Any?> = when (parsed) {
            is List<*> -> parsed
            is Map<*, *> -> PIN_LIST_KEYS
                .firstNotNullOfOrNull { parsed[it] as? List<*> }
                ?: parsed.values.firstNotNullOfOrNull { it as? List<*> }
                ?: return emptyList<PinnedMessageEntity>() to emptyList()
            else -> return emptyList<PinnedMessageEntity>() to emptyList()
        }
        val now = System.currentTimeMillis()
        val pins = ArrayList<PinnedMessageEntity>(entries.size)
        val embedded = ArrayList<MessageDto>(entries.size)
        entries.forEachIndexed { index, entry ->
            pinMessageIdOf(entry)?.let { id ->
                pins += PinnedMessageEntity(
                    conversationId = conversationId,
                    messageId = id,
                    pinnedAt = now - index,
                )
            }
            (entry as? Map<*, *>)?.get("message")?.let { raw ->
                runCatching { messageAdapter.fromJsonValue(raw) }.getOrNull()
                    ?.let { embedded += it }
            }
        }
        return pins to embedded
    }

    /** `entry` is a bare id string or an object carrying the message id. */
    private fun pinMessageIdOf(entry: Any?): String? = when (entry) {
        is String -> entry.takeIf { it.isNotBlank() }
        is Map<*, *> -> (entry["messageId"] as? String)
            ?: (entry["id"] as? String)
            ?: ((entry["message"] as? Map<*, *>)?.get("id") as? String)
            ?: ((entry["message"] as? Map<*, *>)?.get("messageId") as? String)
        else -> null
    }

    // ---- internals ----

    private suspend fun <T> call(
        block: suspend () -> retrofit2.Response<T>,
    ): Result<T> =
        when (val result = safeApiCallCatching(block)) {
            is ApiResult.Success -> Result.Success(result.body)
            is ApiResult.HttpError ->
                Result.Failure(ApiError.from(result.status, result.errorCode))
            is ApiResult.NetworkError ->
                Result.Failure(ApiError.Network(result.cause.message))
        }

    /**
     * safeApiCall casts an empty 2xx body to `Unit as T` — an erased cast that
     * only blows up when `Success.body` is later read as a non-Unit type
     * (same guard as the other repositories).
     */
    private suspend fun <T> safeApiCallCatching(
        block: suspend () -> retrofit2.Response<T>,
    ): ApiResult<T> =
        try {
            safeApiCall(block)
        } catch (_: ClassCastException) {
            ApiResult.HttpError(status = 200, errorCode = "UNEXPECTED_EMPTY_BODY")
        }

    private companion object {
        const val EVENT_BUFFER = 32

        /** Envelope keys `GET /pins` may wrap the id list in. */
        val PIN_LIST_KEYS = listOf("items", "pins", "messageIds", "pinnedMessageIds")
    }
}

/** ISO-8601 → epoch ms, tolerating `…Z` and offset forms; falls back to now(). */
internal fun isoToEpochMs(iso: String?): Long {
    if (iso.isNullOrBlank()) return System.currentTimeMillis()
    return runCatching { Instant.parse(iso).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
        .getOrElse { System.currentTimeMillis() }
}

/** Extracts the `sub` claim from a JWT without a JWT library (payload part only). */
internal fun userIdFromJwt(accessToken: String, moshi: Moshi): String? {
    val parts = accessToken.split('.')
    if (parts.size < 2) return null
    val payload = runCatching {
        String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
    }.getOrNull() ?: return null
    val claims = runCatching { moshi.adapter(Map::class.java).fromJson(payload) }.getOrNull()
        ?: return null
    return claims["sub"] as? String
}
