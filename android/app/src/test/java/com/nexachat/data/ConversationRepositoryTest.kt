package com.nexachat.data

import android.content.Context
import com.nexachat.core.database.ConversationDao
import com.nexachat.core.database.ConversationEntity
import com.nexachat.core.network.GroupApi
import com.nexachat.core.network.MessageApi
import com.nexachat.core.network.dto.ConversationInboxDto
import com.nexachat.core.network.dto.ConversationMemberDto
import com.nexachat.core.network.stomp.RealtimeSession
import com.nexachat.core.storage.TokenStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.util.Base64
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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
    private val moshi = Moshi.Builder().build()

    private lateinit var repository: ConversationRepository

    private val inboxAdapter = Moshi.Builder().build().adapter<List<ConversationInboxDto>>(
        Types.newParameterizedType(List::class.java, ConversationInboxDto::class.java),
    )

    @Before
    fun setUp() {
        every { session.events } returns MutableSharedFlow()
        every { session.resyncRequests } returns MutableSharedFlow()
        coEvery { tokenStore.readTokens() } returns
            TokenStore.Tokens(jwtFor(MY_ID), Long.MAX_VALUE, "refresh")
    }

    private fun TestScope.buildRepository() {
        // backgroundScope — init collectors are infinite; the scope cancels
        // them when the test body finishes instead of hanging runTest.
        repository = ConversationRepository(
            messageApi = messageApi,
            groupApi = groupApi,
            conversationDao = conversationDao,
            session = session,
            tokenStore = tokenStore,
            moshi = moshi,
            scope = backgroundScope,
            context = context,
        )
    }

    private fun stubInbox(items: List<ConversationInboxDto>) {
        coEvery { messageApi.getConversations(any(), any()) } returns
            Response.success(inboxAdapter.toJson(items).toResponseBody())
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
    ) = ConversationInboxDto(
        id = id,
        type = type,
        title = title,
        members = members,
    )

    private fun member(userId: String, role: String) = ConversationMemberDto(
        userId = userId,
        displayName = "n-$userId",
        role = role,
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
                )
            }
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
