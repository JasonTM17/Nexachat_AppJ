package com.nexachat.data

import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.database.ConversationDao
import com.nexachat.core.database.ConversationEntity
import com.nexachat.core.network.ApiResult
import com.nexachat.core.network.GroupApi
import com.nexachat.core.network.MessageApi
import com.nexachat.core.network.dto.AddGroupMembersRequest
import com.nexachat.core.network.dto.ConversationDto
import com.nexachat.core.network.dto.ConversationInboxDto
import com.nexachat.core.network.dto.ConversationMemberDto
import com.nexachat.core.network.dto.ConversationsPageDto
import com.nexachat.core.network.dto.CreateGroupRequest
import com.nexachat.core.network.dto.UpdateConversationRequest
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

    /** GROUP rows render sender names above incoming bubbles and the detail affordance. */
    val isGroup: Boolean get() = type == TYPE_GROUP
}

/**
 * Group member as the feature layer sees it. [role] stays nullable — early
 * server builds may omit it from `members[]`, in which case [isAdmin] is
 * false and the UI hides ADMIN-only affordances (best-effort gate; the
 * server remains the real authorization boundary).
 */
data class GroupMember(
    val userId: String,
    val displayName: String,
    val phone: String?,
    val avatarUrl: String?,
    val role: String?,
) {
    val isAdmin: Boolean get() = role == ROLE_ADMIN

    companion object {
        const val ROLE_ADMIN = "ADMIN"
    }
}

/**
 * Detail projection for `group/{id}/detail`. Members are fetch-only —
 * `ConversationEntity` does not persist them, so this is resolved from the
 * inbox payload (`ConversationInboxDto.members`) rather than Room. [title]
 * doubles as a fallback header while the Room row is still absent.
 */
data class GroupDetail(
    val title: String?,
    val members: List<GroupMember>,
)

/** Conversation `type` wire value for groups (DIRECT lives in the companion below). */
private const val TYPE_GROUP = "GROUP"

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
    private val groupApi: GroupApi,
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
        return when (val result = fetchInboxItems()) {
            is Result.Success -> {
                conversationDao.upsertAll(result.value.mapNotNull { it.toEntity(myId) })
                Result.Success(Unit)
            }
            is Result.Failure -> result
        }
    }

    // ---- group management ----

    /** Our own user id (JWT `sub`), used to resolve the caller's member row. */
    suspend fun myUserId(): String? = currentUserId()

    /**
     * Creates a GROUP conversation and write-throughs the row so the new
     * chat screen has a title before the server broadcast lands. Returns the
     * new conversation id.
     */
    suspend fun createGroup(title: String, memberIds: List<String>): Result<String> {
        val trimmed = title.trim()
        return when (
            val result = call {
                groupApi.createGroup(CreateGroupRequest(title = trimmed, memberIds = memberIds))
            }
        ) {
            is Result.Success -> {
                // The 201 ConversationResponse carries no title — keep the
                // requested one so the fresh chat/inbox row renders correctly.
                conversationDao.upsert(result.value.toEntity(fallbackTitle = trimmed))
                Result.Success(result.value.id)
            }
            is Result.Failure -> result
        }
    }

    /**
     * Member list + title for the group detail screen. There is no assumed
     * `GET /conversations/{id}` on the parallel server build, so this derives
     * the projection from the inbox payload (`ConversationInboxDto.members`)
     * — the same call [refreshInbox] makes — and write-throughs what it finds
     * so Room stays warm. `limit` widens the window for busy inboxes.
     */
    suspend fun groupDetail(conversationId: String): Result<GroupDetail> =
        when (val result = fetchInboxItems(limit = MEMBERS_PAGE_LIMIT)) {
            is Result.Success -> {
                val myId = currentUserId()
                val inbox = result.value.firstOrNull { it.id == conversationId }
                    ?: return Result.Failure(ApiError.Unknown("CONVERSATION_NOT_FOUND"))
                conversationDao.upsert(inbox.toEntity(myId))
                Result.Success(
                    GroupDetail(
                        title = inbox.title,
                        members = inbox.members.mapNotNull { it.toGroupMember() },
                    ),
                )
            }
            is Result.Failure -> result
        }

    suspend fun addGroupMembers(conversationId: String, userIds: List<String>): Result<Unit> =
        call { groupApi.addMembers(conversationId, AddGroupMembersRequest(userIds)) }
            .toUnitResult()

    suspend fun removeGroupMember(conversationId: String, userId: String): Result<Unit> =
        call { groupApi.removeMember(conversationId, userId) }.toUnitResult()

    /**
     * Renames the group (ADMIN-only server-side). On success the local row is
     * patched so `conversationFlow` re-emits without waiting for the
     * CONVERSATION_UPDATED broadcast.
     */
    suspend fun renameGroup(conversationId: String, title: String): Result<Unit> =
        when (
            val result = call {
                groupApi.updateConversation(
                    conversationId,
                    UpdateConversationRequest(title = title.trim()),
                )
            }
        ) {
            is Result.Success -> {
                conversationDao.byId(conversationId)
                    ?.copy(title = title.trim())
                    ?.let { conversationDao.upsert(it) }
                Result.Success(Unit)
            }
            is Result.Failure -> result
        }

    /**
     * Self-leave. The DAO has no delete-by-id, so the stale row is cleared by
     * resetting the table and re-pulling the authoritative inbox — the same
     * wipe-and-refill path logout uses, scoped to the one table we own here.
     */
    suspend fun leaveGroup(conversationId: String): Result<Unit> =
        when (val result = call { groupApi.leaveGroup(conversationId) }) {
            is Result.Success -> {
                conversationDao.clear()
                refreshInbox()
            }
            is Result.Failure -> result
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
            lastMessageSeq = resolvedLastSeq() ?: 0L,
            lastMessageContent = resolvedLastContent(),
            lastMessageSenderName = resolvedLastSender(),
            lastMessageAt = resolvedLastAt()?.let { isoToEpochMs(it) },
            unreadCount = unreadCount ?: 0,
            otherUserName = resolvedOtherName(myId),
            otherUserAvatar = resolvedOtherAvatar(),
        )

    /**
     * `POST /conversations/group` 201 body → fresh Room row (no messages yet).
     * The response omits `title`, so the caller's requested value is the
     * fallback until the first authoritative inbox refresh lands.
     */
    private fun ConversationDto.toEntity(fallbackTitle: String): ConversationEntity = ConversationEntity(
        id = id,
        type = type ?: TYPE_GROUP,
        title = title ?: fallbackTitle,
        lastMessageSeq = 0L,
        lastMessageContent = null,
        lastMessageSenderName = null,
        lastMessageAt = null,
        unreadCount = 0,
        otherUserName = null,
        otherUserAvatar = null,
    )

    private fun ConversationMemberDto.toGroupMember(): GroupMember? =
        userId?.let { id ->
            GroupMember(
                userId = id,
                displayName = displayName ?: phone.orEmpty(),
                phone = phone,
                avatarUrl = avatarUrl,
                role = role,
            )
        }

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

    /**
     * Shared fetch+parse behind [refreshInbox] and [groupDetail]: pulls the
     * inbox and tolerates both response shapes (envelope or bare array).
     */
    private suspend fun fetchInboxItems(limit: Int? = null): Result<List<ConversationInboxDto>> =
        when (val result = call { messageApi.getConversations(limit = limit) }) {
            is Result.Success -> {
                val body = runCatching { result.value.string().trim() }.getOrNull()
                    ?: return Result.Failure(ApiError.Network("body read failed"))
                val items = parseInboxBody(body)
                if (items == null) {
                    Result.Failure(ApiError.Unknown("UNEXPECTED_SHAPE"))
                } else {
                    Result.Success(items)
                }
            }
            is Result.Failure -> result
        }

    private fun parseInboxBody(body: String): List<ConversationInboxDto>? = when {
        body.isEmpty() -> emptyList()
        body.startsWith("[") ->
            runCatching { listAdapter.fromJson(body) }.getOrNull()
        else ->
            runCatching { pageAdapter.fromJson(body) }.getOrNull()?.items
    }

    /** Collapses a `Result<ResponseBody>`-style call into `Result<Unit>`. */
    private fun <T> Result<T>.toUnitResult(): Result<Unit> = when (this) {
        is Result.Success -> Result.Success(Unit)
        is Result.Failure -> this
    }

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

        /**
         * Inbox page size for the member-list fetch — the conversation must
         * be inside the first page since no single-conversation GET exists.
         */
        const val MEMBERS_PAGE_LIMIT = 100
    }
}
