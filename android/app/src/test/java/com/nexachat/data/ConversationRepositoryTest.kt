package com.nexachat.data

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import app.cash.turbine.test
import com.nexachat.core.database.ConversationDao
import com.nexachat.core.database.ConversationEntity
import com.nexachat.core.network.GroupApi
import com.nexachat.core.network.MessageApi
import com.nexachat.core.network.dto.ConversationDto
import com.nexachat.core.network.dto.ConversationInboxDto
import com.nexachat.core.network.dto.ConversationMemberDto
import com.nexachat.core.network.stomp.ChatEvent
import com.nexachat.core.network.stomp.RealtimeSession
import com.nexachat.core.storage.TokenStore
import com.nexachat.core.storage.Tokens
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Base64
import java.io.InputStream
import java.util.Arrays
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Call
import retrofit2.Response

/**
 * Covers the refresh-reconcile contract: `GET /conversations` returns the
 * authoritative FULL set, so Room rows absent from it must be deleted
 * (member removal, deleted conversation). Upserts alone leave dead rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationRepositoryTest {

    private val messageApi = mockk<MessageApi>()
    private val groupApi = mockk<GroupApi>()
    private val session = mockk<RealtimeSession>()
    private val tokenStore = mockk<TokenStore>()
    private val context = mockk<Context>(relaxed = true)
    private val conversationDao = FakeConversationDao()
    private val presenceRepository = mockk<PresenceRepository>(relaxed = true)
    private val presenceFlow = MutableStateFlow<Map<String, PeerPresence>>(emptyMap())
    private val moshi = Moshi.Builder().build()
    private val chatEvents = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 8)

    private lateinit var repository: ConversationRepository

    private val inboxAdapter = Moshi.Builder().build().adapter<List<ConversationInboxDto>>(
        Types.newParameterizedType(List::class.java, ConversationInboxDto::class.java),
    )

    @Before
    fun setUp() {
        every { session.events } returns chatEvents
        every { session.resyncRequests } returns MutableSharedFlow()
        every { presenceRepository.presences } returns presenceFlow
        coEvery { tokenStore.readTokens() } returns
            Tokens(jwtFor(MY_ID), Long.MAX_VALUE, "refresh")
    }

    private fun TestScope.buildRepository() {
        // backgroundScope — init collectors are infinite; the scope cancels
        // them when the test body finishes instead of hanging runTest.
        repository = ConversationRepository(
            messageApi = messageApi,
            groupApi = groupApi,
            conversationDao = conversationDao,
            presenceRepository = presenceRepository,
            session = session,
            tokenStore = tokenStore,
            moshi = moshi,
            scope = backgroundScope,
            context = context,
        )
    }

    private fun <T> Response<T>.asCall(): Call<T> = mockk<Call<T>> {
        every { execute() } returns this@asCall
    }

    private fun stubInbox(items: List<ConversationInboxDto>) {
        every { messageApi.getConversations(any(), any()) } returns
            Response.success(inboxAdapter.toJson(items).toResponseBody()).asCall()
    }

    @Test
    fun `refreshInbox drops rows the server no longer returns`() = runTest {
        buildRepository()
        conversationDao.upsertAll(
            listOf(conversation("conv-a"), conversation("conv-removed")),
        )
        stubInbox(listOf(inboxDto("conv-a")))

        repository.refreshInbox()

        assertEquals(setOf("conv-a"), conversationDao.rows.keys)
    }

    @Test
    fun `refreshInbox clears all rows on an empty authoritative set`() = runTest {
        buildRepository()
        conversationDao.upsertAll(
            listOf(conversation("conv-a"), conversation("conv-b")),
        )
        stubInbox(emptyList())

        repository.refreshInbox()

        assertTrue(conversationDao.rows.isEmpty())
    }

    @Test
    fun `refreshInbox maps the caller member role into the row`() = runTest {
        buildRepository()
        stubInbox(
            listOf(
                inboxDto(
                    "conv-g",
                    type = "GROUP",
                    title = "Team",
                    members = listOf(
                        member(MY_ID, "ADMIN"),
                        member("u-other", "MEMBER"),
                    ),
                ),
            ),
        )

        repository.refreshInbox()

        assertEquals("ADMIN", conversationDao.rows["conv-g"]?.myRole)
    }

    @Test
    fun `refreshInbox preserves peerReadSeq and a known role across payload gaps`() = runTest {
        buildRepository()
        conversationDao.upsert(
            conversation("conv-a").copy(peerReadSeq = 42L, myRole = "MEMBER"),
        )
        // A payload spelling without members[] must not strip client state.
        stubInbox(listOf(inboxDto("conv-a", members = emptyList())))

        repository.refreshInbox()

        val row = conversationDao.rows["conv-a"]!!
        assertEquals(42L, row.peerReadSeq)
        assertEquals("MEMBER", row.myRole)
    }

    @Test
    fun `refreshInbox keeps a group this device just created when the payload predates it`() = runTest {
        buildRepository()
        every { groupApi.createGroup(any()) } returns
            Response.success(ConversationDto("conv-new", "GROUP")).asCall()
        // A GET already in flight when the create committed cannot list the
        // new group — the reconcile prune must not delete it for that reason.
        stubInbox(listOf(inboxDto("conv-old")))

        repository.createGroup("New group", listOf("u-1"))
        repository.refreshInbox()

        assertEquals(setOf("conv-old", "conv-new"), conversationDao.rows.keys)
    }

    @Test
    fun `a created group is pruned like any row once a payload has listed it`() = runTest {
        buildRepository()
        every { groupApi.createGroup(any()) } returns
            Response.success(ConversationDto("conv-new", "GROUP")).asCall()
        repository.createGroup("New group", listOf("u-1"))

        stubInbox(listOf(inboxDto("conv-old"), inboxDto("conv-new")))
        repository.refreshInbox()

        // The protection is a one-shot grace window, not a permanent exemption.
        stubInbox(listOf(inboxDto("conv-old")))
        repository.refreshInbox()

        assertEquals(setOf("conv-old"), conversationDao.rows.keys)
    }

    @Test
    fun `refreshInbox asks for the complete inbox and never a page`() = runTest {
        buildRepository()
        stubInbox(listOf(inboxDto("conv-a")))

        repository.refreshInbox()

        // Pruning is only safe while this call carries no limit/cursor: an
        // optional-paging server would otherwise return page 1 only.
        verify(exactly = 1) { messageApi.getConversations(null, null) }
    }

    @Test
    fun `a paginated inbox payload is never used to prune`() = runTest {
        buildRepository()
        conversationDao.upsertAll(
            listOf(conversation("conv-a"), conversation("conv-b")),
        )
        // An envelope that admits another page is not the authoritative set.
        every { messageApi.getConversations(any(), any()) } returns
            Response.success("""{"items":[{"id":"conv-a"}],"hasMore":true}""".toResponseBody()).asCall()

        repository.refreshInbox()

        assertEquals(setOf("conv-a", "conv-b"), conversationDao.rows.keys)
    }

    @Test
    fun `groupDetail follows the response cursor to later inbox pages`() = runTest {
        buildRepository()
        val firstPage = """[{"id":"conv-first","type":"GROUP","title":"First","members":[]}]"""
        val secondPage = """[{"id":"conv-target","type":"GROUP","title":"Target","members":[]}]"""
        val headers = okhttp3.Headers.Builder()
            .add("X-Next-Cursor", "cursor-page-2")
            .build()
        every {
            messageApi.getConversations(100, null)
        } returns Response.success(firstPage.toResponseBody(), headers).asCall()
        every {
            messageApi.getConversations(100, "cursor-page-2")
        } returns Response.success(secondPage.toResponseBody()).asCall()

        val result = repository.groupDetail("conv-target")

        assertEquals("Target", (result as com.nexachat.core.common.Result.Success).value.title)
        verify(exactly = 1) { messageApi.getConversations(100, null) }
        verify(exactly = 1) {
            messageApi.getConversations(100, "cursor-page-2")
        }
    }

    @Test
    fun `group avatar read stops after the configured byte cap`() = runTest {
        buildRepository()
        val uri = mockk<Uri>()
        val maxBytes = 25 * 1024 * 1024
        var consumed = 0
        val input = object : InputStream() {
            override fun read(): Int = if (consumed++ < maxBytes * 2) 0 else -1

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val remaining = maxBytes * 2 - consumed
                if (remaining <= 0) return -1
                val count = minOf(length, remaining)
                Arrays.fill(buffer, offset, offset + count, 0.toByte())
                consumed += count
                return count
            }
        }
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        every { resolver.openInputStream(uri) } returns input

        val result = repository.setGroupAvatar("conv-g", uri)

        assertTrue(result is com.nexachat.core.common.Result.Failure)
        assertTrue(consumed <= maxBytes + 64 * 1024)
        io.mockk.verify(exactly = 0) { groupApi.uploadAvatar(any(), any()) }
    }

    @Test
    fun `a DIRECT row seeds peer presence and exposes it on the summary`() = runTest {
        buildRepository()
        every { messageApi.getConversations(any(), any()) } returns Response.success(
            """
            [{"id":"conv-d","type":"DIRECT",
              "otherUser":{"userId":"u-peer","displayName":"Peer"},
              "peerPresence":{"online":true,"lastSeenAt":null}}]
            """.trimIndent().toResponseBody(),
        ).asCall()

        repository.refreshInbox()
        presenceFlow.value = mapOf("u-peer" to PeerPresence(online = true, lastSeenAtEpochMs = null))
        val summary = repository.inboxFlow().first().single()

        assertEquals("u-peer", summary.otherUserId)
        assertEquals(PeerPresence(true, null), summary.peerPresence)
        coVerify { presenceRepository.seed(mapOf("u-peer" to PeerPresence(true, null))) }
    }

    @Test
    fun `a deleted last message is flagged for the neutral inbox snippet`() = runTest {
        buildRepository()
        every { messageApi.getConversations(any(), any()) } returns Response.success(
            """
            [{"id":"conv-d","type":"DIRECT",
              "lastMessageSeq":7,
              "lastMessage":{"seq":7,"type":"TEXT","content":null,
                             "deletedAt":"2026-11-03T11:00:00Z"}}]
            """.trimIndent().toResponseBody(),
        ).asCall()

        repository.refreshInbox()
        val summary = repository.inboxFlow().first().single()

        assertEquals(true, summary.lastMessageDeleted)
        assertEquals(null, summary.lastMessageContent)
    }

    @Test
    fun `a DIRECT row without peer presence clears the stale presence seed`() = runTest {
        buildRepository()
        presenceFlow.value = mapOf("u-peer" to PeerPresence(online = true, lastSeenAtEpochMs = null))
        every { presenceRepository.clear(any()) } answers {
            presenceFlow.value = presenceFlow.value - firstArg<String>()
        }
        every { messageApi.getConversations(any(), any()) } returns Response.success(
            """[{"id":"conv-d","type":"DIRECT","otherUser":{"userId":"u-peer"}}]"""
                .toResponseBody(),
        ).asCall()

        repository.refreshInbox()

        assertTrue("stale peer presence remained visible", presenceFlow.value.isEmpty())
        io.mockk.verify { presenceRepository.clear("u-peer") }
    }

    @Test
    fun `conversation updated signals only its matching group detail listener`() = runTest {
        buildRepository()

        repository.groupUpdates("conv-a").test {
            runCurrent()
            chatEvents.emit(ChatEvent.ConversationUpdated("conv-a"))
            runCurrent()
            assertEquals(Unit, awaitItem())

            chatEvents.emit(ChatEvent.ConversationUpdated("conv-b"))
            runCurrent()
            expectNoEvents()

            chatEvents.emit(ChatEvent.ConversationUpdated(null))
            runCurrent()
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun conversation(id: String) = ConversationEntity(
        id = id,
        type = "GROUP",
        title = id,
        lastMessageSeq = 0L,
        lastMessageContent = null,
        lastMessageSenderName = null,
        lastMessageAt = null,
        unreadCount = 0,
        otherUserName = null,
        otherUserAvatar = null,
        avatarUrl = null,
    )

    private fun inboxDto(
        id: String,
        type: String = "GROUP",
        title: String? = "T",
        members: List<ConversationMemberDto> = listOf(member(MY_ID, "MEMBER")),
    ) = ConversationInboxDto(id, type, title, members)

    private fun member(userId: String, role: String) = ConversationMemberDto(
        userId,
        "n-$userId",
        role,
    )

    private fun jwtFor(userId: String): String {
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"sub\":\"$userId\"}".toByteArray(Charsets.UTF_8))
        return "header.$payload.signature"
    }

    private class FakeConversationDao : ConversationDao {
        val rows = LinkedHashMap<String, ConversationEntity>()

        override suspend fun upsert(conversation: ConversationEntity) {
            rows[conversation.id] = conversation
        }

        override suspend fun upsertAll(conversations: List<ConversationEntity>) {
            conversations.forEach { rows[it.id] = it }
        }

        override fun inboxFlow(): Flow<List<ConversationEntity>> =
            MutableStateFlow(rows.values.toList())

        override fun observeById(conversationId: String): Flow<ConversationEntity?> =
            MutableStateFlow(rows[conversationId])

        override suspend fun byId(conversationId: String): ConversationEntity? =
            rows[conversationId]

        override suspend fun byIds(ids: List<String>): List<ConversationEntity> =
            ids.mapNotNull { rows[it] }

        override suspend fun bumpPeerReadSeq(conversationId: String, seq: Long) {
            val row = rows[conversationId] ?: return
            rows[conversationId] = row.copy(peerReadSeq = maxOf(row.peerReadSeq, seq))
        }

        override suspend fun clearUnread(conversationId: String) {
            rows[conversationId] = rows[conversationId]?.copy(unreadCount = 0) ?: return
        }

        override suspend fun updateLastMessage(
            conversationId: String,
            seq: Long,
            content: String?,
            senderName: String?,
            at: Long,
        ) {
            val row = rows[conversationId] ?: return
            if (seq > row.lastMessageSeq) {
                rows[conversationId] = row.copy(
                    lastMessageSeq = seq,
                    lastMessageContent = content,
                    lastMessageSenderName = senderName,
                    lastMessageAt = at,
                    lastMessageDeleted = false,
                )
            }
        }

        override suspend fun updateLastMessageContent(
            conversationId: String,
            seq: Long,
            content: String?,
        ) {
            val row = rows[conversationId] ?: return
            if (row.lastMessageSeq == seq) {
                rows[conversationId] = row.copy(
                    lastMessageContent = content,
                    lastMessageDeleted = false,
                )
            }
        }

        override suspend fun markLastMessageDeleted(conversationId: String) {
            val row = rows[conversationId] ?: return
            rows[conversationId] = row.copy(lastMessageContent = null, lastMessageDeleted = true)
        }

        override suspend fun incrementUnread(conversationId: String, delta: Int) {
            rows[conversationId] = rows[conversationId]
                ?.copy(unreadCount = rows[conversationId]!!.unreadCount + delta)
                ?: return
        }

        override suspend fun clear() {
            rows.clear()
        }

        override suspend fun deleteAllExcept(ids: List<String>) {
            rows.keys.retainAll(ids)
        }
    }

    private companion object {
        const val MY_ID = "me-1"
    }
}
