package com.nexachat.data

import android.content.Context
import android.net.Uri
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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

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
    /** GROUP avatar set by an admin; null for DIRECT rows and unstyled groups. */
    val avatarUrl: String?,
    /** Highest other-member read watermark (MESSAGE_READ) — DIRECT "seen" receipts. */
    val peerReadSeq: Long = 0,
    /** My member role from the inbox payload — GROUP affordance gating (pin etc.). */
    val myRole: String? = null,
) {
    val displayName: String get() = title ?: otherUserName ?: ""

    /** Row avatar: the group image wins; DIRECT rows fall back to the peer's. */
    val displayAvatar: String? get() = avatarUrl ?: otherUserAvatar

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
    @ApplicationContext private val context: Context,
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
    private val groupUpdateIds = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Conversations created by this device whose row may not be in a GET
     * /conversations response that was already in flight when the create
     * committed. [refreshInbox] prunes everything the payload omits, so
     * without this a brand-new group could be deleted from Room right after
     * its write-through. Entries leave the set once a fetch lists them.
     */
    private val locallyCreatedIds = ConcurrentHashMap.newKeySet<String>()

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

    /** Signals server updates for a group whose detail screen is currently open. */
    fun groupUpdates(conversationId: String): Flow<Unit> =
        groupUpdateIds.filter { it == conversationId }.map { Unit }

    // ---- refresh ----

    /**
     * Pulls the inbox and writes rows through. Tolerant of the two plausible
     * response shapes — `{items,nextBeforeSeq,hasMore}` envelope or a bare
     * array (see MessageApi.getConversations).
     *
     * Invariant: the prune below assumes the payload is the caller's COMPLETE
     * inbox. `GET /conversations` returns exactly that when it carries no
     * `limit`/`cursor` (contract: optional keyset paging, default = full list),
     * and this is the only caller without a limit. If paging is ever adopted
     * here, this function must stop pruning from a page — page 2+ rows would
     * be deleted — and rely on [InboxFetch.complete] alone to decide.
     */
    suspend fun refreshInbox(): Result<Unit> {
        val myId = currentUserId()
        return when (val result = fetchInboxItems()) {
            is Result.Success -> {
                val rows = result.value.items.mapNotNull { it.toEntity(myId) }
                upsertPreservingClientColumns(rows)
                // Authoritative full set — drop rows the server no longer
                // returns (removed membership, deleted conversation). Without
                // this a MEMBER_REMOVED leaves a dead row in the inbox that
                // still opens and fails only on send. Rows this device just
                // created survive until the first payload that lists them.
                if (result.value.complete) {
                    val fetchedIds = rows.mapTo(HashSet()) { it.id }
                    val keep = fetchedIds + locallyCreatedIds
                    if (keep.isEmpty()) conversationDao.clear()
                    else conversationDao.deleteAllExcept(keep.toList())
                    locallyCreatedIds.removeAll(fetchedIds)
                }
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
                // Registered before the write-through: a refresh already in
                // flight must not prune the row it cannot know about yet.
                locallyCreatedIds += result.value.id
                upsertPreservingClientColumns(listOf(result.value.toEntity(fallbackTitle = trimmed)))
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
                val inbox = result.value.items.firstOrNull { it.id == conversationId }
                    ?: return Result.Failure(ApiError.Unknown("CONVERSATION_NOT_FOUND"))
                upsertPreservingClientColumns(listOf(inbox.toEntity(myId)))
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
     * Group avatar upload (ADMIN-only server-side, FR-D4). Same Uri→multipart
     * contract as the user avatar; on success the local row is patched so the
     * inbox re-emits immediately — other members get it via
     * CONVERSATION_UPDATED → inbox refresh.
     */
    suspend fun setGroupAvatar(conversationId: String, uri: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes().takeIf { it.size <= MAX_AVATAR_BYTES }
                }
            } catch (e: IOException) {
                null
            } ?: return@withContext Result.Failure(ApiError.Network("avatar read failed"))
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                ?: "image/jpeg"
            val part = MultipartBody.Part.createFormData(
                "file",
                uri.lastPathSegment ?: "group-avatar",
                bytes.toRequestBody(mime.toMediaTypeOrNull()),
            )
            when (val result = call { groupApi.uploadAvatar(conversationId, part) }) {
                is Result.Success -> {
                    conversationDao.byId(conversationId)
                        ?.copy(avatarUrl = result.value.avatarUrl)
                        ?.let { conversationDao.upsert(it) }
                    Result.Success(Unit)
                }
                is Result.Failure -> result
            }
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
                is ChatEvent.ConversationUpdated -> {
                    event.conversationId?.let { groupUpdateIds.tryEmit(it) }
                    refreshRequests.tryEmit(Unit)
                }
                is ChatEvent.MessageCreated -> refreshRequests.tryEmit(Unit)
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
            otherUserAvatar = resolvedOtherAvatar(myId),
            avatarUrl = avatarUrl,
            myRole = members.firstOrNull { it.userId == myId }?.role,
        )

    /**
     * `POST /conversations/group` 201 body → fresh Room row (no messages yet).
     * The response omits `title`, so the caller's requested value is the
     * fallback until the first authoritative inbox refresh lands. The creator
     * is the group's first ADMIN by contract.
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
        avatarUrl = avatarUrl,
        myRole = GroupMember.ROLE_ADMIN,
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
        avatarUrl = avatarUrl,
        peerReadSeq = peerReadSeq,
        myRole = myRole,
    )

    // ---- internals ----

    /**
     * `peerReadSeq` is accumulated client-side from MESSAGE_READ events —
     * REST payloads never carry it. The REPLACE upsert would wipe it on
     * every refresh, so each write merges the stored value back in.
     */
    private suspend fun upsertPreservingClientColumns(rows: List<ConversationEntity>) {
        if (rows.isEmpty()) return
        val prior = conversationDao.byIds(rows.map { it.id })
            .associateBy { it.id }
        conversationDao.upsertAll(
            rows.map { row ->
                row.copy(
                    peerReadSeq = prior[row.id]?.peerReadSeq ?: 0L,
                    // Payloads without members[] must not strip a known role —
                    // a stale ADMIN is safer than hiding a real admin's pin.
                    myRole = row.myRole ?: prior[row.id]?.myRole,
                )
            },
        )
    }

    /**
     * Shared fetch+parse behind [refreshInbox] and [groupDetail]: pulls the
     * inbox and tolerates both response shapes (envelope or bare array).
     * [InboxFetch.complete] is false when the envelope admits another page —
     * the caller must not treat that payload as the full set.
     */
    private suspend fun fetchInboxItems(limit: Int? = null): Result<InboxFetch> =
        when (val result = call { messageApi.getConversations(limit = limit) }) {
            is Result.Success -> {
                val body = runCatching { result.value.string().trim() }.getOrNull()
                    ?: return Result.Failure(ApiError.Network("body read failed"))
                val page = parseInboxBody(body)
                if (page == null) {
                    Result.Failure(ApiError.Unknown("UNEXPECTED_SHAPE"))
                } else {
                    Result.Success(page)
                }
            }
            is Result.Failure -> result
        }

    private fun parseInboxBody(body: String): InboxFetch? = when {
        body.isEmpty() -> InboxFetch(emptyList(), complete = true)
        body.startsWith("[") ->
            runCatching { listAdapter.fromJson(body) }.getOrNull()
                ?.let { InboxFetch(it, complete = true) }
        else ->
            runCatching { pageAdapter.fromJson(body) }.getOrNull()
                ?.let {
                    InboxFetch(
                        items = it.items,
                        complete = !it.hasMore && it.nextBeforeSeq == null,
                    )
                }
    }

    /** Inbox items plus whether the payload was the authoritative full set. */
    private data class InboxFetch(
        val items: List<ConversationInboxDto>,
        val complete: Boolean,
    )

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

        // Server hard cap is 25 MB; a group avatar should never approach it.
        const val MAX_AVATAR_BYTES = 25 * 1024 * 1024
    }
}
