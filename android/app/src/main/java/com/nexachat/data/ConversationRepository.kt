package com.nexachat.data

import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.database.ConversationDao
import com.nexachat.core.database.ConversationEntity
import com.nexachat.core.network.ApiResult
import com.nexachat.core.network.MessageApi
import com.nexachat.core.network.dto.ConversationInboxDto
import com.nexachat.core.network.dto.ConversationsPageDto
import com.nexachat.core.network.safeApiCall
import com.nexachat.core.network.stomp.ChatEvent
import com.nexachat.core.network.stomp.RealtimeSession
import com.nexachat.core.storage.TokenStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Inbox row as the feature layer sees it — [displayName] is already resolved
 * (group `title` beats peer name; a bare `[OTHER]` never reaches the UI).
 */
data class ConversationSummary(
    val id: String,
    val type: String,
    /** Group title, or null for DIRECT rows. */
    val title: String?,
    val lastMessageContent: String?,
    val lastMessageSenderName: String?,
    val lastMessageAtEpochMs: Long?,
    val lastMessageSeq: Long,
    val unreadCount: Int,
    val otherUserName: String?,
    val otherUserAvatar: String?,
) {
    val displayName: String get() = title ?: otherUserName ?: ""
}

/**
 * Conversation/inbox boundary (plan §10.2 `GET /conversations`, §12.2 UDF).
 *
 * Room is the render source: [inboxFlow] is a pure DB stream; [refreshInbox]
 * is the authoritative write-through. WS pushes only *trigger* refreshes —
 * a debounced collector folds CONVERSATION_UPDATED and MESSAGE_CREATED bursts
 * into one REST call, covering both "new last message" and "conversation I
 * never heard of" without per-event traffic. [resyncRequests] does the same
 * after every reconnect (REST is the source of truth — §11.3.5).
 */
@Singleton
@OptIn(FlowPreview::class)
class ConversationRepository @Inject constructor(
    private val messageApi: MessageApi,
    private val conversationDao: ConversationDao,
    private val session: RealtimeSession,
    private val tokenStore: TokenStore,
    private val moshi: Moshi,
    private val scope: CoroutineScope,
) {

    private val pageAdapter = moshi.adapter(ConversationsPageDto::class.java)
    private val listAdapter = moshi.adapter<List<ConversationInboxDto>>(
        Types.newParameterizedType(List::class.java, ConversationInboxDto::class.java),
    )

    /**
     * Small fan-in so a burst of WS events coalesces into ONE REST refresh.
     * Declared before `init` — property initializers run in order.
     */
    private val refreshRequests = MutableSharedFlow<Unit>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        scope.launch { watchPushTriggers() }
        scope.launch {
            refreshRequests.debounce(PUSH_REFRESH_DEBOUNCE_MS).collect { refreshInbox() }
        }
        scope.launch {
            session.resyncRequests.collect { refreshInbox() }
        }
    }

    // ---- read model ----

    fun inboxFlow(): Flow<List<ConversationSummary>> =
        conversationDao.inboxFlow().map { rows -> rows.map { it.toSummary() } }

    /** Single conversation for the chat title bar. */
    fun conversationFlow(conversationId: String): Flow<ConversationSummary?> =
        conversationDao.observeById(conversationId).map { it?.toSummary() }

    // ---- refresh ----

    /**
     * Pulls the inbox and writes rows through. Tolerant of the two plausible
     * response shapes — `{items,nextBeforeSeq,hasMore}` envelope or a bare
     * array (see MessageApi.getConversations).
     */
    suspend fun refreshInbox(): Result<Unit> {
        val myId = currentUserId()
        return when (val result = call { messageApi.getConversations() }) {
            is Result.Success -> {
                val body = runCatching { result.value.string().trim() }.getOrNull()
                    ?: return Result.Failure(ApiError.Network("body read failed"))
                val items = when {
                    body.isEmpty() -> emptyList()
                    body.startsWith("[") ->
                        runCatching { listAdapter.fromJson(body) }.getOrNull()
                    else ->
                        runCatching { pageAdapter.fromJson(body) }.getOrNull()?.items
                }
                if (items == null) {
                    Result.Failure(ApiError.Unknown("UNEXPECTED_SHAPE"))
                } else {
                    conversationDao.upsertAll(items.mapNotNull { it.toEntity(myId) })
                    Result.Success(Unit)
                }
            }
            is Result.Failure -> result
        }
    }

    // ---- push triggers ----

    private suspend fun watchPushTriggers() {
        session.events.collect { event ->
            when (event) {
                is ChatEvent.ConversationUpdated,
                is ChatEvent.MessageCreated,
                -> refreshRequests.tryEmit(Unit)
                else -> Unit
            }
        }
    }

    // ---- mappers ----

    private suspend fun currentUserId(): String? {
        val access = tokenStore.readTokens()?.accessToken ?: return null
        return userIdFromJwt(access, moshi)
    }

    private fun ConversationInboxDto.toEntity(myId: String?): ConversationEntity =
        ConversationEntity(
            id = id,
            type = type ?: TYPE_DIRECT,
            title = title,
            lastMessageSeq = lastMessageSeq ?: 0L,
            lastMessageContent = resolvedLastContent(),
            lastMessageSenderName = resolvedLastSender(),
            lastMessageAt = resolvedLastAt()?.let { isoToEpochMs(it) },
            unreadCount = unreadCount ?: 0,
            otherUserName = resolvedOtherName(myId),
            otherUserAvatar = resolvedOtherAvatar(),
        )

    private fun ConversationEntity.toSummary(): ConversationSummary = ConversationSummary(
        id = id,
        type = type,
        title = title,
        lastMessageContent = lastMessageContent,
        lastMessageSenderName = lastMessageSenderName,
        lastMessageAtEpochMs = lastMessageAt,
        lastMessageSeq = lastMessageSeq,
        unreadCount = unreadCount,
        otherUserName = otherUserName,
        otherUserAvatar = otherUserAvatar,
    )

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

    /** Same empty-body cast guard as the other repositories. */
    private suspend fun <T> safeApiCallCatching(
        block: suspend () -> retrofit2.Response<T>,
    ): ApiResult<T> =
        try {
            safeApiCall(block)
        } catch (_: ClassCastException) {
            ApiResult.HttpError(status = 200, errorCode = "UNEXPECTED_EMPTY_BODY")
        }

    private companion object {
        const val TYPE_DIRECT = "DIRECT"
        const val PUSH_REFRESH_DEBOUNCE_MS = 400L
    }
}
