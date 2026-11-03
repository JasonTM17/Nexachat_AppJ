package com.nexachat.data

import app.cash.turbine.test
import com.nexachat.core.database.ConversationDao
import com.nexachat.core.database.ConversationEntity
import com.nexachat.core.database.MessageDao
import com.nexachat.core.database.MessageEntity
import com.nexachat.core.database.OutboxDao
import com.nexachat.core.database.OutboxEntity
import com.nexachat.core.database.OutboxState
import com.nexachat.core.network.MessageApi
import com.nexachat.core.network.SessionEvents
import com.nexachat.core.network.dto.AttachmentDto
import com.nexachat.core.network.dto.MessageDto
import com.nexachat.core.network.dto.MessagePageDto
import com.nexachat.core.network.stomp.ChatEvent
import com.nexachat.core.network.stomp.RealtimeSession
import com.nexachat.core.storage.TokenStore
import com.nexachat.core.network.dto.SendMessageRequest
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Send-pipeline coverage (plan §11.3.3 dedup, §12.2 outbox):
 * SENDING → WS SEND → MESSAGE_CREATED ack by clientMessageId → SENT, the
 * REST fallback while WS is down, FAILED marking on rejection, and the
 * messagesFlow merge ordering.
 *
 * DAOs are hand fakes — Room needs instrumentation, but the interface
 * contract is what the repository depends on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageRepositoryTest {

    // ---- hand fakes ----

    private class FakeMessageDao : MessageDao {
        val rows = LinkedHashMap<String, MessageEntity>()
        private val flows = MutableStateFlow(0)

        private fun bump() {
            flows.value++
        }

        override suspend fun upsert(message: MessageEntity) {
            if (!rows.containsKey(message.id)) rows[message.id] = message // IGNORE semantics
            bump()
        }

        override suspend fun upsertAll(messages: List<MessageEntity>) {
            messages.forEach { if (!rows.containsKey(it.id)) rows[it.id] = it }
            bump()
        }

        override fun messagesForConversation(conversationId: String): Flow<List<MessageEntity>> =
            kotlinx.coroutines.flow.flow {
                flows.collect {
                    emit(
                        rows.values
                            .filter { it.conversationId == conversationId }
                            .sortedBy { it.seq },
                    )
                }
            }

        override suspend fun maxSeq(conversationId: String): Long =
            rows.values.filter { it.conversationId == conversationId }.maxOfOrNull { it.seq } ?: 0L

        override suspend fun deleteAll() {
            rows.clear()
            bump()
        }
    }

    private class FakeOutboxDao : OutboxDao {
        val rows = LinkedHashMap<String, OutboxEntity>()
        private val pendingFlow = MutableStateFlow<List<OutboxEntity>>(emptyList())

        private fun sync() {
            pendingFlow.value = rows.values.sortedBy { it.createdAt }
        }

        override suspend fun insert(item: OutboxEntity) {
            rows[item.clientMessageId] = item
            sync()
        }

        override suspend fun updateState(clientMessageId: String, state: String) {
            rows[clientMessageId] = rows[clientMessageId]?.copy(state = state) ?: return
            sync()
        }

        override fun pendingOrdered(): Flow<List<OutboxEntity>> = pendingFlow

        override suspend fun pendingList(): List<OutboxEntity> =
            rows.values.filter { it.state == OutboxState.SENDING }.sortedBy { it.createdAt }

        override suspend fun byId(clientMessageId: String): OutboxEntity? = rows[clientMessageId]

        override suspend fun delete(clientMessageId: String) {
            rows.remove(clientMessageId)
            sync()
        }

        override suspend fun clear() {
            rows.clear()
            sync()
        }
    }

    private class FakeConversationDao : ConversationDao {
        val rows = LinkedHashMap<String, ConversationEntity>()
        val unreadBumps = mutableListOf<Pair<String, Int>>()

        override suspend fun upsert(conversation: ConversationEntity) {
            rows[conversation.id] = conversation
        }

        override suspend fun upsertAll(conversations: List<ConversationEntity>) {
            conversations.forEach { rows[it.id] = it }
        }

        override fun inboxFlow(): Flow<List<ConversationEntity>> = MutableStateFlow(rows.values.toList())

        override fun observeById(conversationId: String): Flow<ConversationEntity?> =
            MutableStateFlow(rows[conversationId])

        override suspend fun byId(conversationId: String): ConversationEntity? = rows[conversationId]

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
            unreadBumps += conversationId to delta
            rows[conversationId] = rows[conversationId]
                ?.copy(unreadCount = rows[conversationId]!!.unreadCount + delta)
                ?: return
        }

        override suspend fun clear() {
            rows.clear()
        }
    }

    // ---- collaborators ----

    private val messageDao = FakeMessageDao()
    private val outboxDao = FakeOutboxDao()
    private val conversationDao = FakeConversationDao()
    private val messageApi: MessageApi = mockk()
    private val session: RealtimeSession = mockk()
    private val tokenStore: TokenStore = mockk()
    private val sessionEvents: SessionEvents = mockk()
    private val moshi = Moshi.Builder().build()
    private val dispatcher = UnconfinedTestDispatcher()

    private val eventsFlow = MutableSharedFlow<ChatEvent>(extraBufferCapacity = 16)
    private val isOpenFlow = MutableStateFlow(true)
    private val resyncFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    private val logoutFlow = MutableSharedFlow<SessionEvents.Event>(extraBufferCapacity = 4)

    private lateinit var repository: MessageRepository

    private fun jwtFor(userId: String): String {
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"sub\":\"$userId\"}".toByteArray(Charsets.UTF_8))
        return "header.$payload.signature"
    }

    private fun messageDto(
        id: String,
        clientMessageId: String?,
        senderId: String = "u-other",
        seq: Long = 1,
    ) = MessageDto(
        id = id,
        seq = seq,
        conversationId = CONVERSATION_ID,
        senderId = senderId,
        clientMessageId = clientMessageId,
        type = "TEXT",
        content = "hi",
        createdAt = "2026-11-03T09:12:04Z",
    )

    @Before
    fun setUp() {
        every { session.events } returns eventsFlow
        every { session.isOpen } returns isOpenFlow
        every { session.resyncRequests } returns resyncFlow
        every { session.sendMessage(any(), any()) } returns true
        every { session.sendTyping(any()) } returns true
        every { session.watchConversation(any()) } just Runs
        every { session.unwatchConversation(any()) } just Runs
        every { session.ensureConnected() } just Runs
        every { sessionEvents.events } returns logoutFlow
        coEvery { tokenStore.readTokens() } returns
            TokenStore.Tokens(jwtFor("me-1"), Long.MAX_VALUE, "refresh")
        coEvery { messageApi.getMessages(any(), any(), any(), any()) } returns
            Response.success(MessagePageDto())
        coEvery { messageApi.markRead(any(), any()) } returns Response.success(Unit)

        repository = MessageRepository(
            messageApi = messageApi,
            messageDao = messageDao,
            conversationDao = conversationDao,
            outboxDao = outboxDao,
            session = session,
            tokenStore = tokenStore,
            sessionEvents = sessionEvents,
            moshi = moshi,
            scope = CoroutineScope(dispatcher),
        )
        // Start the repository's background collectors deterministically.
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `send enqueues a SENDING outbox row and dispatches over STOMP`() = runTest {
        repository.send(CONVERSATION_ID, "hello")

        val row = outboxDao.rows.values.single()
        assertEquals(OutboxState.SENDING, row.state)
        assertEquals("hello", row.content)
        verify(exactly = 1) {
            session.sendMessage(CONVERSATION_ID, match { it.clientMessageId == row.clientMessageId })
        }
    }

    @Test
    fun `MESSAGE_CREATED with the same clientMessageId completes SENT dedup`() = runTest {
        repository.send(CONVERSATION_ID, "hello")
        val clientId = outboxDao.rows.keys.single()

        eventsFlow.emit(
            ChatEvent.MessageCreated(
                message = messageDto(id = "m-1", clientMessageId = clientId, senderId = "me-1"),
                conversationId = CONVERSATION_ID,
                serverTs = null,
            ),
        )

        assertTrue(outboxDao.rows.isEmpty())
        assertEquals(listOf("m-1"), messageDao.rows.keys.toList())

        // A redelivery of the same event never produces a second row.
        eventsFlow.emit(
            ChatEvent.MessageCreated(
                message = messageDto(id = "m-1", clientMessageId = clientId, senderId = "me-1"),
                conversationId = CONVERSATION_ID,
                serverTs = null,
            ),
        )
        assertEquals(1, messageDao.rows.size)
    }

    @Test
    fun `send while WS is down falls back to the REST post`() = runTest {
        isOpenFlow.value = false
        every { session.sendMessage(any(), any()) } returns false
        coEvery { messageApi.sendMessage(any(), any()) } answers {
            // Server echoes the idempotency key — the ack path needs it.
            Response.success(
                messageDto(
                    id = "m-2",
                    clientMessageId = secondArg<SendMessageRequest>().clientMessageId,
                    senderId = "me-1",
                ),
            )
        }

        repository.send(CONVERSATION_ID, "offline hi")

        assertTrue(outboxDao.rows.isEmpty())
        assertEquals("m-2", messageDao.rows.keys.single())
        coVerify(exactly = 1) { messageApi.sendMessage(CONVERSATION_ID, any()) }
    }

    @Test
    fun `send with no connectivity stays SENDING for the next flush`() = runTest {
        isOpenFlow.value = false
        every { session.sendMessage(any(), any()) } returns false
        coEvery { messageApi.sendMessage(any(), any()) } throws IOException("offline")

        repository.send(CONVERSATION_ID, "queued")

        assertEquals(OutboxState.SENDING, outboxDao.rows.values.single().state)
    }

    @Test
    fun `server rejection marks the outbox row FAILED`() = runTest {
        isOpenFlow.value = false
        every { session.sendMessage(any(), any()) } returns false
        coEvery { messageApi.sendMessage(any(), any()) } returns
            Response.error(403, "".toResponseBody())

        repository.send(CONVERSATION_ID, "rejected")

        assertEquals(OutboxState.FAILED, outboxDao.rows.values.single().state)
    }

    @Test
    fun `error-queue event marks the matching row FAILED`() = runTest {
        repository.send(CONVERSATION_ID, "hello")
        val clientId = outboxDao.rows.keys.single()

        eventsFlow.emit(ChatEvent.SendRejected(code = "NOT_MEMBER", clientMessageId = clientId))

        assertEquals(OutboxState.FAILED, outboxDao.rows.values.single().state)
    }

    @Test
    fun `retry requeues a FAILED row and dispatches again`() = runTest {
        repository.send(CONVERSATION_ID, "hello")
        val clientId = outboxDao.rows.keys.single()
        outboxDao.updateState(clientId, OutboxState.FAILED)

        repository.retry(clientId)

        assertEquals(OutboxState.SENDING, outboxDao.rows.values.single().state)
        verify(atLeast = 2) { session.sendMessage(CONVERSATION_ID, any()) }
    }

    @Test
    fun `incoming message from a peer bumps unread when the chat is not open`() = runTest {
        conversationDao.upsert(
            ConversationEntity(
                id = CONVERSATION_ID,
                type = "DIRECT",
                title = null,
                lastMessageSeq = 0,
                lastMessageContent = null,
                lastMessageSenderName = null,
                lastMessageAt = null,
                unreadCount = 0,
                otherUserName = "Lan",
                otherUserAvatar = null,
            ),
        )

        eventsFlow.emit(
            ChatEvent.MessageCreated(
                message = messageDto(id = "m-7", clientMessageId = null, senderId = "u-other", seq = 7),
                conversationId = CONVERSATION_ID,
                serverTs = null,
            ),
        )

        assertEquals(CONVERSATION_ID to 1, conversationDao.unreadBumps.single())
        assertEquals(1, conversationDao.rows[CONVERSATION_ID]?.unreadCount)
        assertEquals(7L, conversationDao.rows[CONVERSATION_ID]?.lastMessageSeq)
    }

    @Test
    fun `messagesFlow merges sent history and pending outbox rows`() = runTest {
        messageDao.upsert(
            MessageEntity(
                id = "m-1",
                conversationId = CONVERSATION_ID,
                seq = 1,
                senderId = "u-other",
                senderDisplayName = "Lan",
                type = "TEXT",
                content = "old",
                clientMessageId = null,
                replyToId = null,
                createdAt = 1L,
                attachmentsJson = null,
            ),
        )

        repository.messagesFlow(CONVERSATION_ID).test {
            assertEquals(1, awaitItem().size)

            repository.send(CONVERSATION_ID, "queued")

            val merged = awaitItem()
            assertEquals(2, merged.size)
            assertEquals(DeliveryStatus.SENT, merged[0].status)
            assertEquals(DeliveryStatus.SENDING, merged[1].status)
            assertTrue(merged[1].mine)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `currentUserId decodes the JWT sub claim`() = runTest {
        assertEquals("me-1", repository.currentUserId())
        assertTrue(repository.isMine("me-1"))
        assertTrue(!repository.isMine("u-other"))
    }

    @Test
    fun `flushOutbox dispatches SENDING rows oldest-first`() = runTest {
        outboxDao.insert(
            OutboxEntity("cm-a", CONVERSATION_ID, "TEXT", "first", null, OutboxState.SENDING, 1L),
        )
        outboxDao.insert(
            OutboxEntity("cm-b", CONVERSATION_ID, "TEXT", "second", null, OutboxState.SENDING, 2L),
        )
        outboxDao.insert(
            OutboxEntity("cm-c", CONVERSATION_ID, "TEXT", "failed", null, OutboxState.FAILED, 3L),
        )
        val sentContents = mutableListOf<String>()
        every { session.sendMessage(any(), any()) } answers {
            sentContents += secondArg<SendMessageRequest>().content.orEmpty()
            true
        }

        repository.flushOutbox()

        // FAILED rows are not retried by the flush; SENDING go oldest-first.
        assertEquals(listOf("first", "second"), sentContents)
    }

    @Test
    fun `send with attachments persists them on the outbox and sends attachmentIds`() = runTest {
        val attachment = AttachmentDto(id = "att-1", mimeType = "image/png", originalName = "pic.png")
        var captured: SendMessageRequest? = null
        every { session.sendMessage(any(), any()) } answers {
            captured = secondArg()
            true
        }

        repository.send(
            CONVERSATION_ID,
            "pic.png",
            type = "IMAGE",
            attachments = listOf(attachment),
        )

        val row = outboxDao.rows.values.single()
        assertEquals("IMAGE", row.type)
        assertTrue(row.attachmentsJson.isNotBlank())
        assertEquals(listOf("att-1"), captured?.attachmentIds)

        // The pending bubble carries the same attachment model a SENT row would.
        repository.messagesFlow(CONVERSATION_ID).test {
            val pending = awaitItem().single()
            assertEquals(DeliveryStatus.SENDING, pending.status)
            assertEquals(listOf(attachment), pending.attachments)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `text sends carry no attachmentIds`() = runTest {
        var captured: SendMessageRequest? = null
        every { session.sendMessage(any(), any()) } answers {
            captured = secondArg()
            true
        }

        repository.send(CONVERSATION_ID, "plain")

        assertEquals(emptyList<String>(), captured?.attachmentIds)
        assertEquals("", outboxDao.rows.values.single().attachmentsJson)
    }

    @Test
    fun `resyncForward pages forward from local watermark until contiguous`() = runTest {
        // Room already holds seq 1..100; the server has 101..105 (two pages).
        (1..100).forEach { seq ->
            messageDao.upsert(
                MessageEntity(
                    id = "m-$seq", conversationId = CONVERSATION_ID, seq = seq.toLong(),
                    senderId = "u-other", senderDisplayName = null, type = "TEXT",
                    content = "old", clientMessageId = null, replyToId = null,
                    createdAt = seq.toLong(), attachmentsJson = null,
                ),
            )
        }
        coEvery {
            messageApi.getMessages(CONVERSATION_ID, null, 100L, any())
        } returns Response.success(
            MessagePageDto(
                items = listOf(
                    messageDto("m-101", null, seq = 101),
                    messageDto("m-102", null, seq = 102),
                    messageDto("m-103", null, seq = 103),
                ),
                hasMore = true,
            ),
        )
        coEvery {
            messageApi.getMessages(CONVERSATION_ID, null, 103L, any())
        } returns Response.success(
            MessagePageDto(
                items = listOf(
                    messageDto("m-104", null, seq = 104),
                    messageDto("m-105", null, seq = 105),
                ),
                hasMore = false,
            ),
        )

        repository.resyncForward(CONVERSATION_ID)

        // All five missed rows landed — the gap is closed, not just the newest page.
        assertEquals(105, messageDao.rows.size)
        coVerify { messageApi.getMessages(CONVERSATION_ID, null, 103L, any()) }
    }

    private companion object {
        const val CONVERSATION_ID = "c-1"
    }
}
