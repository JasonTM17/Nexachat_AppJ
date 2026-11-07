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
import com.nexachat.core.network.dto.EditMessageRequest
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
import java.util.UUID
import kotlin.jvm.JvmOverloads
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

private data class DeleteRollback(
    val before: MessageEntity,
    val optimistic: MessageEntity,
    val reactions: List<MessageReactionEntity>,
    val pin: PinnedMessageEntity?,
    val wasLast: Boolean,
)

/**
 * The message pipeline (plan §12.2):
 *
 *   send() → OutboxEntity(SENDING) → STOMP SEND → ack by MESSAGE_CREATED with
 *   the same clientMessageId → row deleted + real message upserted (SENT).
 *   WS down → REST POST fallback → success acks identically; a server
 *   rejection (`/user/queue/errors` or HTTP error) flips the row to FAILED —
 *   tap-to-retry requeues it. RATE_LIMITED and transport failure keep SENDING;
 *   the delayed rate-limit flush or reconnect ([resyncRequests]) retries in order.
 *
 * Room is the only render source — WS events are written through, never
 * rendered directly — so dedup by message id (IGNORE) and clientMessageId is
 * the load-bearing rule of §11.3.3.
 */
internal class MessageRepositoryCore(
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

    /** A delete event that races an in-flight optimistic DELETE must win rollback. */
    private val pendingDeleteIds = ConcurrentHashMap.newKeySet<String>()
    private val confirmedDeleteIds = ConcurrentHashMap.newKeySet<String>()

    /** Coalesces RATE_LIMITED responses into one delayed outbox flush. */
    private val rateLimitRetryLock = Any()
    private var rateLimitRetryJob: Job? = null
    private val lastOutboxCreatedAt = AtomicLong(0L)

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
                    pin.messageId,
                    pin.conversationId,
                    null,
                    "",
                    null,
                    MessageDto.TYPE_TEXT,
                    null,
                    null,
                    null,
                    null,
                    pin.pinnedAt,
                    null,
                    DeliveryStatus.SENT,
                    false,
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
        // Forward-fill Room before marking read: reconnect resync only runs on
        // resyncRequests, so pushes missed while this topic was unwatched (or
        // rows lost to a logout wipe) would otherwise stay invisible forever.
        resyncForward(conversationId)
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
    @JvmOverloads
    suspend fun refreshHistory(
        conversationId: String,
        beforeSeq: Long? = null,
    ): Result<MessagePageDto> =
        when (
            val result = call {
                messageApi.getMessages(
                    conversationId,
                    beforeSeq,
                    null,
                    MessageApi.DEFAULT_PAGE_SIZE,
                )
            }
        ) {
            is Result.Success -> {
                storeHistoryItems(conversationId, result.value.items)
                Result.Success(result.value)
            }
            is Result.Failure -> result
        }

    /**
     * Pages history backwards until [messageId] is stored, so a quote block or
     * pin banner can scroll to a message outside the loaded window. Bounded by
     * [maxPages]; each page goes through the same write-through as normal
     * paging, so the list re-renders the target as soon as it lands.
     */
    @JvmOverloads
    suspend fun loadUntilLoaded(
        conversationId: String,
        messageId: String,
        maxPages: Int = MAX_JUMP_PAGES,
    ): MessageJump {
        if (messageDao.exists(messageId)) return MessageJump.Found.INSTANCE
        if (messageDao.minSeq(conversationId) <= 0L) {
            // Nothing loaded yet (cold screen, failed first page): pull the
            // newest page before paging backwards from a non-existent cursor.
            val first = refreshHistory(conversationId)
            if (first is Result.Failure) return MessageJump.Failed(first.error)
            if (messageDao.exists(messageId)) return MessageJump.Found.INSTANCE
        }
        repeat(maxPages) {
            val oldest = messageDao.minSeq(conversationId)
            if (oldest <= 0L) return MessageJump.NotFound.INSTANCE
            when (val page = refreshHistory(conversationId, beforeSeq = oldest)) {
                is Result.Success -> {
                    if (messageDao.exists(messageId)) return MessageJump.Found.INSTANCE
                    if (!page.value.hasMore) return MessageJump.NotFound.INSTANCE
                }
                is Result.Failure -> return MessageJump.Failed(page.error)
            }
        }
        return MessageJump.NotFound.INSTANCE
    }

    /**
     * Write-through shared by history paging and reconnect resync: message
     * rows upsert by id, and per-message `reactions` summaries replace
     * wholesale — but ONLY for messages that actually carried the field
     * (`null` = absent, must not wipe local state).
     */
    private suspend fun storeHistoryItems(conversationId: String, items: List<MessageDto>) {
        val deletedEntities = mutableListOf<MessageEntity>()
        for (dto in items) {
            val entity = dto.toEntity(conversationId) ?: continue
            val existing = messageDao.byId(entity.id)
            when {
                existing == null -> messageDao.upsert(entity)
                // Delete always wins over a stale live page (never resurrect).
                existing.deletedAtEpochMs == null &&
                    entity.deletedAtEpochMs != null ->
                    messageDao.update(
                        existing.copy(
                            content = "",
                            attachmentsJson = null,
                            deletedAtEpochMs = entity.deletedAtEpochMs,
                        ),
                    )
                // A reconnect page carries edits made while offline; IGNORE
                // alone would leave the row stale forever.
                existing.deletedAtEpochMs == null &&
                    (existing.content != entity.content ||
                        existing.editedAtEpochMs != entity.editedAtEpochMs) ->
                    messageDao.update(
                        existing.copy(
                            content = entity.content,
                            editedAtEpochMs = entity.editedAtEpochMs
                                ?: existing.editedAtEpochMs,
                        ),
                    )
            }
            if (entity.deletedAtEpochMs != null) {
                deletedEntities += entity
                if (conversationDao.byId(entity.conversationId)?.lastMessageSeq == entity.seq) {
                    conversationDao.markLastMessageDeleted(entity.conversationId)
                }
            }
        }
        for (entity in deletedEntities) {
            reactionDao.deleteForMessage(entity.id)
            pinDao.delete(entity.conversationId, entity.id)
            messageDao.clearReplySnippets(entity.id)
        }
        val myId = currentUserId()
        val reactionRows = mutableListOf<MessageReactionEntity>()
        val reactionOwnerIds = mutableListOf<String>()
        for (dto in items) {
            val existing = messageDao.byId(dto.id)
            if (!dto.deletedAt.isNullOrBlank() || existing?.deletedAtEpochMs != null) continue
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

    // ---- edit / delete ----

    /**
     * Edit own TEXT message (F1). Optimistic Room write, then the REST PATCH;
     * a failure restores the exact prior row unless a fresher server write
     * landed during the flight (compare-and-restore, same rule as reactions).
     * Identical/blank content never hits the wire — the server treats both as
     * no-ops and would publish no event anyway.
     */
    suspend fun editMessage(
        conversationId: String,
        messageId: String,
        newContent: String,
    ): Result<Unit> {
        val trimmed = newContent.trim()
        if (trimmed.isEmpty()) return Result.failure(ApiError.Unknown(ERROR_VALIDATION_FAILED))
        val before = messageDao.byId(messageId)
            ?: return Result.failure(ApiError.Unknown(ERROR_MESSAGE_NOT_FOUND))
        // Mirror the server boundary locally: only the author may edit, and
        // only TEXT. The server remains authoritative for stale rows.
        if (!isMine(before.senderId)) {
            return Result.failure(ApiError.Unknown(ERROR_NOT_MESSAGE_AUTHOR))
        }
        if (before.type != MessageDto.TYPE_TEXT) {
            return Result.failure(ApiError.Unknown(ERROR_VALIDATION_FAILED))
        }
        if (before.deletedAtEpochMs != null) {
            return Result.failure(ApiError.Unknown(ERROR_MESSAGE_DELETED))
        }
        if (before.content == trimmed) return Result.Success(Unit)
        val optimistic = before.copy(
            content = trimmed,
            editedAtEpochMs = System.currentTimeMillis(),
        )
        messageDao.update(optimistic)
        return try {
            when (
                val result = call {
                    messageApi.editMessage(conversationId, messageId, EditMessageRequest(trimmed))
                }
            ) {
                is Result.Success -> {
                    // Apply the response only if no newer event changed the row
                    // while PATCH was in flight. A delete or later edit event
                    // already owns the Room row and its inbox projection.
                    if (messageDao.byId(messageId) == optimistic) {
                        result.value.toEntity(conversationId)?.takeIf {
                            it.deletedAtEpochMs == null
                        }?.let { server ->
                            val authoritative = server.copy(
                                editedAtEpochMs = server.editedAtEpochMs
                                    ?: optimistic.editedAtEpochMs,
                            )
                            messageDao.update(authoritative)
                            refreshLastMessagePreview(authoritative)
                        }
                    }
                    Result.Success(Unit)
                }
                is Result.Failure -> {
                    if (messageDao.byId(messageId) == optimistic) messageDao.update(before)
                    Result.cast(result)
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (messageDao.byId(messageId) == optimistic) messageDao.update(before)
            }
            throw cancelled
        }
    }

    /**
     * Delete for everyone (F2). Optimistic tombstone: content blanked,
     * attachments detached, reactions and any pin dropped, then the REST
     * DELETE. A failure restores the row and the interaction side-tables —
     * but only while nothing fresher replaced them.
     */
    suspend fun deleteMessage(conversationId: String, messageId: String): Result<Unit> {
        // Coalesce concurrent taps; the first request owns optimistic state.
        if (!pendingDeleteIds.add(messageId)) return Result.Success(Unit)
        confirmedDeleteIds.remove(messageId)
        var rollback: DeleteRollback? = null
        try {
            val before = messageDao.byId(messageId)
                ?: return Result.failure(ApiError.Unknown(ERROR_MESSAGE_NOT_FOUND))
            if (!isMine(before.senderId)) {
                return Result.failure(ApiError.Unknown(ERROR_NOT_MESSAGE_AUTHOR))
            }
            // Idempotent by contract: an already-tombstoned row is a local success.
            if (before.deletedAtEpochMs != null) return Result.Success(Unit)
            val reactionsBefore = reactionDao.forMessage(messageId)
            val pinBefore = pinDao.find(conversationId, messageId)
            val wasLast = isLastMessage(before)
            val optimistic = before.copy(
                content = "",
                attachmentsJson = null,
                deletedAtEpochMs = System.currentTimeMillis(),
            )
            rollback = DeleteRollback(before, optimistic, reactionsBefore, pinBefore, wasLast)
            messageDao.update(optimistic)
            reactionDao.deleteForMessage(messageId)
            pinDao.delete(conversationId, messageId)
            if (wasLast) conversationDao.markLastMessageDeleted(conversationId)
            return when (
                val result = call { messageApi.deleteMessage(conversationId, messageId) }
                    .toUnitResult()
            ) {
                is Result.Success -> {
                    messageDao.clearReplySnippets(messageId)
                    Result.Success(Unit)
                }
                is Result.Failure -> {
                    if (!confirmedDeleteIds.contains(messageId)) {
                        rollback?.let { rollbackDeleteIfUnchanged(conversationId, it) }
                    }
                    result
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val state = rollback
                if (state != null && !confirmedDeleteIds.contains(messageId)) {
                    rollbackDeleteIfUnchanged(conversationId, state)
                }
            }
            throw cancelled
        } finally {
            pendingDeleteIds.remove(messageId)
            confirmedDeleteIds.remove(messageId)
        }
    }

    private suspend fun rollbackDeleteIfUnchanged(
        conversationId: String,
        state: DeleteRollback,
    ) {
        if (messageDao.byId(state.before.id) != state.optimistic) return
        messageDao.update(state.before)
        if (reactionDao.forMessage(state.before.id).isEmpty()) {
            reactionDao.upsertAll(state.reactions)
        }
        if (state.pin != null && pinDao.find(conversationId, state.before.id) == null) {
            pinDao.upsert(state.pin)
        }
        if (state.wasLast) {
            val conversation = conversationDao.byId(conversationId)
            if (conversation?.lastMessageSeq == state.before.seq && conversation.lastMessageDeleted) {
                conversationDao.updateLastMessageContent(
                    conversationId,
                    state.before.seq,
                    state.before.content ?: state.before.type,
                )
            }
        }
    }

    // ---- search (F4) ----

    /**
     * Server-side search scoped to one conversation. Results are written
     * through to Room (same path as history) so a selected hit is already
     * loaded when the screen calls [loadUntilLoaded]/jump.
     */
    @JvmOverloads
    suspend fun searchMessages(
        conversationId: String,
        query: String,
        beforeSeq: Long? = null,
        limit: Int = MessageApi.DEFAULT_SEARCH_LIMIT,
    ): Result<MessageSearchResult> {
        val trimmed = query.trim()
        if (trimmed.length < MIN_SEARCH_CHARS) {
            return Result.Success(MessageSearchResult(emptyList(), false, null))
        }
        return when (
            val result = call {
                messageApi.searchMessages(
                    conversationId,
                    trimmed,
                    limit.coerceIn(1, MAX_SEARCH_LIMIT),
                    beforeSeq,
                )
            }
        ) {
            is Result.Success -> {
                storeHistoryItems(conversationId, result.value.items)
                val myId = currentUserId()
                val items = result.value.items.mapNotNull { dto ->
                    val entity = dto.toEntity(conversationId) ?: return@mapNotNull null
                    val chips = dto.reactions
                        ?.mapNotNull { it.toEntity(entity.id, conversationId, myId) }
                        ?.map { it.toChip() }
                        .orEmpty()
                    entity.toChatMessage(myId, chips)
                }
                Result.Success(
                    MessageSearchResult(items, result.value.hasMore, result.value.nextBeforeSeq),
                )
            }
            is Result.Failure -> Result.cast(result)
        }
    }

    /** True when [row] is the inbox preview (used for edit/delete refreshes). */
    private suspend fun isLastMessage(row: MessageEntity): Boolean =
        conversationDao.byId(row.conversationId)?.lastMessageSeq == row.seq

    /** Rewrites the inbox snippet after an edit, guarded on the same seq. */
    private suspend fun refreshLastMessagePreview(row: MessageEntity) {
        if (!isLastMessage(row)) return
        conversationDao.updateLastMessageContent(
            row.conversationId,
            row.seq,
            row.content ?: row.type,
        )
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
    @JvmOverloads
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
            createdAt = nextOutboxCreatedAt(),
            attachmentsJson = attachments
                .takeIf { it.isNotEmpty() }
                ?.let { runCatching { attachmentsAdapter.toJson(it) }.getOrNull() }
                .orEmpty(),
        )
        outboxDao.insert(entry)
        dispatchOutbox(entry)
    }

    /** Stable FIFO key despite same-millisecond sends or a wall-clock setback. */
    private suspend fun nextOutboxCreatedAt(): Long {
        val persistedMax = outboxDao.pendingOrdered().first().maxOfOrNull { it.createdAt } ?: 0L
        val now = System.currentTimeMillis()
        return lastOutboxCreatedAt.updateAndGet { previous ->
            maxOf(now, persistedMax + 1L, previous + 1L)
        }
    }

    /** Tap-to-retry on a FAILED bubble: requeue and dispatch again. */
    suspend fun retry(clientMessageId: String) {
        val entry = outboxDao.byId(clientMessageId) ?: return
        outboxDao.insert(
            entry.copy(
                state = OutboxState.SENDING,
                createdAt = nextOutboxCreatedAt(),
            ),
        )
        // Manual retry joins the back of the sending queue. Flushing from the
        // DAO preserves FIFO, so a newer failed bubble cannot overtake older
        // SENDING rows that are still waiting for connectivity.
        flushOutbox()
    }

    /** Flushes every SENDING row in queue order — called on (re)connect. */
    suspend fun flushOutbox() {
        for (entry in outboxDao.pendingList()) {
            dispatchOutbox(entry)
        }
    }

    private suspend fun dispatchOutbox(entry: OutboxEntity) {
        // A send/retry is a user action — re-arm a parked connection loop so
        // the socket doesn't stay silently dead while REST still works.
        session.ensureConnected()
        val request = SendMessageRequest(
            entry.clientMessageId,
            entry.type,
            entry.content,
            entry.replyToId,
            // WS SEND and the REST fallback both carry the same ids (plan §11.2).
            entry.attachments().mapNotNull { it.id },
        )
        if (session.sendMessage(entry.conversationId, request)) {
            return // ack arrives as MESSAGE_CREATED → onMessageCreated
        }
        if (!session.isOpen.value) {
            // WS down → REST fallback (plan §12.2). Permanent HTTP errors
            // (NOT_MEMBER, validation) become FAILED; rate limits and transport
            // failures stay SENDING for their scheduled/reconnect flush.
            when (val result = call { messageApi.sendMessage(entry.conversationId, request) }) {
                is Result.Success -> acknowledge(entry.conversationId, result.value)
                is Result.Failure -> when (result.error) {
                    is ApiError.RateLimited -> scheduleRateLimitRetry()
                    is ApiError.Network -> Unit
                    else -> outboxDao.updateState(entry.clientMessageId, OutboxState.FAILED)
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
        when (call { messageApi.markRead(conversationId, ReadRequest(upToSeq)) }.toUnitResult()) {
            is Result.Success -> conversationDao.clearUnread(conversationId)
            is Result.Failure -> Unit // best-effort — next open/message retries
        }
    }

    // ---- WS event intake ----

    private suspend fun routeSessionEvents() {
        session.events.collect { event ->
            when (event) {
                is ChatEvent.MessageCreated -> onMessageCreated(event)
                is ChatEvent.MessageUpdated -> onMessageUpdated(event)
                is ChatEvent.MessageDeleted -> onMessageDeleted(event)
                is ChatEvent.UserTyping -> event.conversationId?.let { convId ->
                    _typingEvents.tryEmit(TypingEvent(convId, event.userId, event.displayName))
                }
                is ChatEvent.MessageRead -> event.conversationId?.let { convId ->
                    _readEvents.tryEmit(ReadEvent(convId, event.userId, event.upToSeq))
                    // Persist OTHER members' watermarks onto the conversation
                    // row — that's what renders "Đã xem" on my bubbles (FR-C4).
                    // My own read echoes back over the topic and must not
                    // count as a peer receipt.
                    val upToSeq = event.upToSeq
                    if (upToSeq != null && event.userId != null &&
                        event.userId != currentUserId()
                    ) {
                        conversationDao.bumpPeerReadSeq(convId, upToSeq)
                    }
                }
                is ChatEvent.SendRejected -> event.clientMessageId?.let { clientId ->
                    if (event.code == ERROR_RATE_LIMITED) {
                        scheduleRateLimitRetry()
                    } else {
                        outboxDao.updateState(clientId, OutboxState.FAILED)
                    }
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

    /**
     * `MESSAGE_UPDATED` (F1) → apply the authoritative content/editedAt to the
     * stored row. Duplicate or late broadcasts are idempotent: a tombstoned
     * row never resurrects (delete wins), and an unchanged row is not rewritten.
     */
    private suspend fun onMessageUpdated(event: ChatEvent.MessageUpdated) {
        val dto = event.message
        val conversationId = dto.conversationId ?: event.conversationId ?: return
        val server = dto.toEntity(conversationId) ?: return
        val row = messageDao.byId(server.id)
        if (row == null) {
            // The edit can outrun history hydration — storing the payload is
            // safe because INSERT IGNORE keeps any later duplicate a no-op.
            messageDao.upsert(server)
            return
        }
        if (row.deletedAtEpochMs != null) return
        val updated = row.copy(
            content = server.content,
            editedAtEpochMs = server.editedAtEpochMs ?: System.currentTimeMillis(),
        )
        if (updated != row) messageDao.update(updated)
        refreshLastMessagePreview(updated)
    }

    /**
     * `MESSAGE_DELETED` (F2) → tombstone the row, drop reactions/attachments
     * views and any pin. A duplicate broadcast keeps the first `deletedAt`
     * (history continuity) and the side-table deletes stay harmless.
     */
    private suspend fun onMessageDeleted(event: ChatEvent.MessageDeleted) {
        val messageId = event.messageId ?: return
        val conversationId = event.conversationId ?: return
        if (pendingDeleteIds.contains(messageId)) confirmedDeleteIds.add(messageId)
        val row = messageDao.byId(messageId)
        val deletedAt = isoToEpochMsOrNull(event.deletedAt) ?: System.currentTimeMillis()
        if (row != null && row.conversationId == conversationId) {
            if (row.deletedAtEpochMs == null) {
                messageDao.update(
                    row.copy(content = "", attachmentsJson = null, deletedAtEpochMs = deletedAt),
                )
            }
            if (isLastMessage(row)) conversationDao.markLastMessageDeleted(conversationId)
        }
        reactionDao.deleteForMessage(messageId)
        pinDao.delete(conversationId, messageId)
        messageDao.clearReplySnippets(messageId)
    }

    private fun scheduleRateLimitRetry() {
        synchronized(rateLimitRetryLock) {
            if (rateLimitRetryJob?.isActive == true) return
            rateLimitRetryJob = scope.launch {
                delay(RATE_LIMIT_RETRY_DELAY_MS)
                synchronized(rateLimitRetryLock) { rateLimitRetryJob = null }
                flushOutbox()
            }
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
                PinnedMessageEntity(conversationId, messageId, System.currentTimeMillis()),
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
                    ?: return Result.failure(ApiError.Network("body read failed"))
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
            is Result.Failure -> Result.cast(result)
        }

    /** Optimistic pin — revert + signal on failure (403 non-admin, PIN_LIMIT). */
    suspend fun pinMessage(conversationId: String, messageId: String) {
        val entry = PinnedMessageEntity(conversationId, messageId, System.currentTimeMillis())
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
            val result = call {
                messageApi.getMessages(
                    conversationId,
                    null,
                    afterSeq,
                    MessageApi.DEFAULT_PAGE_SIZE,
                )
            }
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
        id,
        conversationId,
        seq,
        senderId,
        senderDisplayName,
        type,
        content,
        replyToId,
        replyToSenderName,
        replyToSnippet,
        createdAt,
        clientMessageId,
        DeliveryStatus.SENT,
        senderId == myId,
        attachments(),
        reactions,
        editedAtEpochMs,
        deletedAtEpochMs,
    )

    private fun OutboxEntity.toChatMessage(): ChatMessage = ChatMessage(
        clientMessageId,
        conversationId,
        null,
        "",
        null,
        type,
        content,
        replyToId,
        null,
        null,
        createdAt,
        clientMessageId,
        if (state == OutboxState.FAILED) DeliveryStatus.FAILED else DeliveryStatus.SENDING,
        true,
        attachments(),
        emptyList(),
        null,
        null,
    )

    private fun MessageDto.toEntity(conversationIdFallback: String?): MessageEntity? {
        val conversation = conversationId ?: conversationIdFallback ?: return null
        // A tombstone never ships content/attachments/reactions — normalise at
        // ingest so Room and every read path see the same neutral shape.
        val deleted = !deletedAt.isNullOrBlank()
        return MessageEntity(
            id = id,
            conversationId = conversation,
            seq = seq ?: 0L,
            senderId = resolvedSenderId.orEmpty(),
            senderDisplayName = resolvedSenderName,
            type = resolvedType,
            content = if (deleted) "" else content,
            clientMessageId = clientMessageId,
            replyToId = resolvedReplyToId,
            replyToSenderName = replyTo?.resolvedSenderName,
            replyToSnippet = replyTo?.resolvedSnippet,
            createdAt = isoToEpochMs(createdAt),
            attachmentsJson = if (deleted) {
                null
            } else {
                attachments
                    .takeIf { it.isNotEmpty() }
                    ?.let { runCatching { attachmentsAdapter.toJson(it) }.getOrNull() }
            },
            editedAtEpochMs = isoToEpochMsOrNull(editedAt),
            deletedAtEpochMs = isoToEpochMsOrNull(deletedAt),
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
        ReactionChip(emoji, count, reactedByMe)

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
                pins += PinnedMessageEntity(conversationId, id, now - index)
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

    private fun <T> Result<T>.toUnitResult(): Result<Unit> = when (this) {
        is Result.Success -> Result.Success(Unit)
        is Result.Failure -> Result.cast(this)
    }

    private suspend fun <T> call(
        block: () -> retrofit2.Call<T>,
    ): Result<T> =
        when (val result = safeApiCallCatching(block())) {
            is ApiResult.Success -> Result.Success(result.body)
            is ApiResult.HttpError ->
                Result.failure(ApiError.from(result.status, result.errorCode))
            is ApiResult.NetworkError ->
                Result.failure(ApiError.Network(result.cause.message))
        }

    /**
     * safeApiCall casts an empty 2xx body to `Unit as T` — an erased cast that
     * only blows up when `Success.body` is later read as a non-Unit type
     * (same guard as the other repositories).
     */
    private suspend fun <T> safeApiCallCatching(
        call: retrofit2.Call<T>,
    ): ApiResult<T> =
        try {
            safeApiCall(call)
        } catch (_: ClassCastException) {
            ApiResult.HttpError(200, "UNEXPECTED_EMPTY_BODY")
        }

    private companion object {
        const val EVENT_BUFFER = 32

        /** Jump-to-message page budget — 20 pages ≈ 600 messages of history. */
        const val MAX_JUMP_PAGES = 20

        /** F4 mirrors the server bounds: query 2..100 chars, page ≤ 50. */
        const val MIN_SEARCH_CHARS = 2
        const val MAX_SEARCH_LIMIT = 50

        const val ERROR_MESSAGE_NOT_FOUND = "MESSAGE_NOT_FOUND"
        const val ERROR_MESSAGE_DELETED = "MESSAGE_DELETED"
        const val ERROR_NOT_MESSAGE_AUTHOR = "NOT_MESSAGE_AUTHOR"
        const val ERROR_VALIDATION_FAILED = "VALIDATION_FAILED"
        const val ERROR_RATE_LIMITED = "RATE_LIMITED"
        const val RATE_LIMIT_RETRY_DELAY_MS = 10_000L

        /** Envelope keys `GET /pins` may wrap the id list in. */
        val PIN_LIST_KEYS = listOf("items", "pins", "messageIds", "pinnedMessageIds")
    }
}

/** ISO-8601 → epoch ms, tolerating `…Z` and offset forms; null when absent/invalid. */
internal fun isoToEpochMsOrNull(iso: String?): Long? = TimeAndJwt.isoToEpochMsOrNull(iso)

/** ISO-8601 → epoch ms, tolerating `…Z` and offset forms; falls back to now(). */
internal fun isoToEpochMs(iso: String?): Long = TimeAndJwt.isoToEpochMs(iso)

/** Extracts the `sub` claim from a JWT without a JWT library (payload part only). */
internal fun userIdFromJwt(accessToken: String, moshi: Moshi): String? =
    TimeAndJwt.userIdFromJwt(accessToken, moshi)
