package com.nexachat.data

import app.cash.turbine.test
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.database.ConversationDao
import com.nexachat.core.database.ConversationEntity
import com.nexachat.core.database.MessageDao
import com.nexachat.core.database.MessageEntity
import com.nexachat.core.database.MessageReactionEntity
import com.nexachat.core.database.OutboxDao
import com.nexachat.core.database.OutboxEntity
import com.nexachat.core.database.OutboxState
import com.nexachat.core.database.PinDao
import com.nexachat.core.database.PinnedMessageEntity
import com.nexachat.core.database.ReactionDao
import com.nexachat.core.network.MessageApi
import com.nexachat.core.network.SessionEvents
import com.nexachat.core.network.dto.AttachmentDto
import com.nexachat.core.network.dto.EditMessageRequest
import com.nexachat.core.network.dto.MessageDto
import com.nexachat.core.network.dto.MessagePageDto
import com.nexachat.core.network.dto.ReactionRequest
import com.nexachat.core.network.dto.ReactionSummaryDto
import com.nexachat.core.network.dto.ReactionUpdateDto
import com.nexachat.core.network.dto.ReplyRefDto
import com.nexachat.core.network.stomp.ChatEvent
import com.nexachat.core.network.stomp.RealtimeSession
import com.nexachat.core.storage.TokenStore
import com.nexachat.core.storage.Tokens
import com.nexachat.core.network.dto.SendMessageRequest
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import java.io.IOException
import okhttp3.ResponseBody
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
import retrofit2.Call
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

        override suspend fun update(message: MessageEntity) {
            if (rows.containsKey(message.id)) {
                rows[message.id] = message
                bump()
            }
        }

        override suspend fun byId(messageId: String): MessageEntity? = rows[messageId]

        override suspend fun clearReplySnippets(messageId: String) {
            for ((id, row) in rows.entries.toList()) {
                if (row.replyToId == messageId) rows[id] = row.copy(replyToSnippet = null)
            }
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

        override suspend fun minSeq(conversationId: String): Long =
            rows.values.filter { it.conversationId == conversationId }.minOfOrNull { it.seq } ?: 0L

        override suspend fun exists(messageId: String): Boolean = rows.containsKey(messageId)

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
            unreadBumps += conversationId to delta
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

    /** Keyed by (messageId, emoji) — mirroring the real PK. */
    private class FakeReactionDao : ReactionDao {
        val rows = LinkedHashMap<Pair<String, String>, MessageReactionEntity>()
        private val flows = MutableStateFlow(0)

        private fun bump() {
            flows.value++
        }

        override suspend fun upsertAll(reactions: List<MessageReactionEntity>) {
            reactions.forEach { rows[it.messageId to it.emoji] = it }
            bump()
        }

        override fun reactionsForConversation(
            conversationId: String,
        ): Flow<List<MessageReactionEntity>> = kotlinx.coroutines.flow.flow {
            flows.collect {
                emit(rows.values.filter { it.conversationId == conversationId })
            }
        }

        override suspend fun forMessage(messageId: String): List<MessageReactionEntity> =
            rows.values.filter { it.messageId == messageId }

        override suspend fun deleteForMessage(messageId: String) {
            rows.keys.removeAll { it.first == messageId }
            bump()
        }

        override suspend fun deleteForMessages(messageIds: List<String>) {
            rows.keys.removeAll { it.first in messageIds }
            bump()
        }

        override suspend fun clear() {
            rows.clear()
            bump()
        }
    }

    /** Keyed by (conversationId, messageId) — mirroring the real PK. */
    private class FakePinDao : PinDao {
        val rows = LinkedHashMap<Pair<String, String>, PinnedMessageEntity>()
        private val flows = MutableStateFlow(0)

        private fun bump() {
            flows.value++
        }

        override suspend fun upsert(pin: PinnedMessageEntity) {
            rows[pin.conversationId to pin.messageId] = pin
            bump()
        }

        override suspend fun upsertAll(pins: List<PinnedMessageEntity>) {
            pins.forEach { rows[it.conversationId to it.messageId] = it }
            bump()
        }

        override fun pinnedFor(conversationId: String): Flow<List<PinnedMessageEntity>> =
            kotlinx.coroutines.flow.flow {
                flows.collect {
                    emit(
                        rows.values
                            .filter { it.conversationId == conversationId }
                            .sortedByDescending { it.pinnedAt },
                    )
                }
            }

        override suspend fun find(conversationId: String, messageId: String): PinnedMessageEntity? =
            rows[conversationId to messageId]

        override suspend fun delete(conversationId: String, messageId: String) {
            rows.remove(conversationId to messageId)
            bump()
        }

        override suspend fun deleteForConversation(conversationId: String) {
            rows.keys.removeAll { it.first == conversationId }
            bump()
        }

        override suspend fun clear() {
            rows.clear()
            bump()
        }
    }

    // ---- collaborators ----

    private val messageDao = FakeMessageDao()
    private val outboxDao = FakeOutboxDao()
    private val conversationDao = FakeConversationDao()
    private val reactionDao = FakeReactionDao()
    private val pinDao = FakePinDao()
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
        id,
        seq,
        CONVERSATION_ID,
        senderId,
        clientMessageId,
        "TEXT",
        "hi",
        "2026-11-03T09:12:04Z",
    )

    private fun <T> Response<T>.asCall(beforeExecute: () -> Unit = {}): Call<T> = mockk<Call<T>> {
        every { execute() } answers {
            beforeExecute()
            this@asCall
        }
    }

    private fun <T> failedCall(error: Throwable): Call<T> = mockk<Call<T>> {
        every { execute() } throws error
    }

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
            Tokens(jwtFor("me-1"), Long.MAX_VALUE, "refresh")
        every { messageApi.getMessages(any(), any(), any(), any()) } returns
            Response.success(MessagePageDto()).asCall()
        every { messageApi.markRead(any(), any()) } returns Response.success<Void>(204, null).asCall()

        repository = MessageRepository(
            messageApi,
            messageDao,
            conversationDao,
            outboxDao,
            reactionDao,
            pinDao,
            session,
            tokenStore,
            sessionEvents,
            moshi,
            CoroutineScope(dispatcher),
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
    fun `new sends sort after persisted outbox rows despite a clock setback`() = runTest {
        val persistedTime = System.currentTimeMillis() + 60_000L
        outboxDao.insert(
            OutboxEntity("cm-persisted", CONVERSATION_ID, "TEXT", "earlier", null, OutboxState.FAILED, persistedTime),
        )

        repository.send(CONVERSATION_ID, "later")

        assertTrue(outboxDao.rows.getValue("cm-persisted").createdAt <
            outboxDao.rows.values.single { it.content == "later" }.createdAt)
    }

    @Test
    fun `MESSAGE_CREATED with the same clientMessageId completes SENT dedup`() = runTest {
        repository.send(CONVERSATION_ID, "hello")
        val clientId = outboxDao.rows.keys.single()

        eventsFlow.emit(
            ChatEvent.MessageCreated(
                messageDto(id = "m-1", clientMessageId = clientId, senderId = "me-1"),
                CONVERSATION_ID,
                null,
            ),
        )

        assertTrue(outboxDao.rows.isEmpty())
        assertEquals(listOf("m-1"), messageDao.rows.keys.toList())

        // A redelivery of the same event never produces a second row.
        eventsFlow.emit(
            ChatEvent.MessageCreated(
                messageDto(id = "m-1", clientMessageId = clientId, senderId = "me-1"),
                CONVERSATION_ID,
                null,
            ),
        )
        assertEquals(1, messageDao.rows.size)
    }

    @Test
    fun `send re-arms the connection loop even when the socket looks dead`() = runTest {
        // Parked-loop wake-up: a user send is the strongest "wants
        // connectivity" signal — dispatchOutbox must call ensureConnected so
        // a loop parked after retry exhaustion gets kicked, not left dead
        // while the REST fallback quietly works.
        isOpenFlow.value = false
        every { session.sendMessage(any(), any()) } returns false
        every { messageApi.sendMessage(any(), any()) } returns
            Response.error<MessageDto>(500, "".toResponseBody(null)).asCall()

        repository.send(CONVERSATION_ID, "wake the loop")

        verify { session.ensureConnected() }
    }

    @Test
    fun `send while WS is down falls back to the REST post`() = runTest {
        isOpenFlow.value = false
        every { session.sendMessage(any(), any()) } returns false
        every { messageApi.sendMessage(any(), any()) } answers {
            // Server echoes the idempotency key — the ack path needs it.
            Response.success(
                messageDto(
                    id = "m-2",
                    clientMessageId = secondArg<SendMessageRequest>().clientMessageId,
                    senderId = "me-1",
                ),
            ).asCall()
        }

        repository.send(CONVERSATION_ID, "offline hi")

        assertTrue(outboxDao.rows.isEmpty())
        assertEquals("m-2", messageDao.rows.keys.single())
        verify(exactly = 1) { messageApi.sendMessage(CONVERSATION_ID, any()) }
    }

    @Test
    fun `send with no connectivity stays SENDING for the next flush`() = runTest {
        isOpenFlow.value = false
        every { session.sendMessage(any(), any()) } returns false
        every { messageApi.sendMessage(any(), any()) } returns failedCall(IOException("offline"))

        repository.send(CONVERSATION_ID, "queued")

        assertEquals(OutboxState.SENDING, outboxDao.rows.values.single().state)
    }

    @Test
    fun `server rejection marks the outbox row FAILED`() = runTest {
        isOpenFlow.value = false
        every { session.sendMessage(any(), any()) } returns false
        every { messageApi.sendMessage(any(), any()) } returns
            Response.error<MessageDto>(403, "".toResponseBody()).asCall()

        repository.send(CONVERSATION_ID, "rejected")

        assertEquals(OutboxState.FAILED, outboxDao.rows.values.single().state)
    }

    @Test
    fun `REST RATE_LIMITED keeps the outbox row and retries after the window`() = runTest {
        isOpenFlow.value = false
        every { session.sendMessage(any(), any()) } returns false
        var calls = 0
        every { messageApi.sendMessage(any(), any()) } answers {
            calls++
            if (calls == 1) {
                Response.error<MessageDto>(429, "".toResponseBody()).asCall()
            } else {
                Response.success(
                    messageDto(
                        id = "m-rate-limited",
                        clientMessageId = secondArg<SendMessageRequest>().clientMessageId,
                        senderId = "me-1",
                    ),
                ).asCall()
            }
        }

        repository.send(CONVERSATION_ID, "queued after rate limit")

        val outbox = outboxDao.rows.values.single()
        assertEquals(OutboxState.SENDING, outbox.state)
        dispatcher.scheduler.advanceTimeBy(9_999)
        dispatcher.scheduler.runCurrent()
        assertEquals("retry ran before the 10s server window", 1, calls)
        dispatcher.scheduler.advanceTimeBy(1)
        dispatcher.scheduler.runCurrent()

        assertEquals(2, calls)
        assertTrue(outboxDao.rows.isEmpty())
        assertEquals(listOf("m-rate-limited"), messageDao.rows.keys.toList())
    }

    @Test
    fun `error-queue event marks the matching row FAILED`() = runTest {
        repository.send(CONVERSATION_ID, "hello")
        val clientId = outboxDao.rows.keys.single()

        eventsFlow.emit(ChatEvent.SendRejected("NOT_MEMBER", clientId))

        assertEquals(OutboxState.FAILED, outboxDao.rows.values.single().state)
    }

    @Test
    fun `error-queue RATE_LIMITED schedules an automatic retry`() = runTest {
        repository.send(CONVERSATION_ID, "hello")
        val clientId = outboxDao.rows.keys.single()

        eventsFlow.emit(ChatEvent.SendRejected("RATE_LIMITED", clientId))

        assertEquals(OutboxState.SENDING, outboxDao.rows.getValue(clientId).state)
        dispatcher.scheduler.advanceTimeBy(10_000)
        dispatcher.scheduler.runCurrent()

        verify(exactly = 2) { session.sendMessage(CONVERSATION_ID, any()) }
        assertEquals(OutboxState.SENDING, outboxDao.rows.getValue(clientId).state)
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
    fun `manual retry joins the tail of the sending queue`() = runTest {
        outboxDao.insert(
            OutboxEntity("cm-failed", CONVERSATION_ID, "TEXT", "retry me", null, OutboxState.FAILED, 1L),
        )
        outboxDao.insert(
            OutboxEntity("cm-waiting", CONVERSATION_ID, "TEXT", "older pending", null, OutboxState.SENDING, 2L),
        )
        val sentContents = mutableListOf<String>()
        every { session.sendMessage(any(), any()) } answers {
            sentContents += secondArg<SendMessageRequest>().content.orEmpty()
            true
        }

        repository.retry("cm-failed")

        assertEquals(listOf("older pending", "retry me"), sentContents)
        assertTrue(
            outboxDao.rows.getValue("cm-failed").createdAt >
                outboxDao.rows.getValue("cm-waiting").createdAt,
        )
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
                avatarUrl = null,
            ),
        )

        eventsFlow.emit(
            ChatEvent.MessageCreated(
                messageDto(id = "m-7", clientMessageId = null, senderId = "u-other", seq = 7),
                CONVERSATION_ID,
                null,
            ),
        )

        assertEquals(CONVERSATION_ID to 1, conversationDao.unreadBumps.single())
        assertEquals(1, conversationDao.rows[CONVERSATION_ID]?.unreadCount)
        assertEquals(7L, conversationDao.rows[CONVERSATION_ID]?.lastMessageSeq)
    }

    @Test
    fun `peer MESSAGE_READ advances peerReadSeq but my own echo does not`() = runTest {
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
                avatarUrl = null,
            ),
        )

        // My own watermark echoing back over the topic must not count as a peer receipt.
        eventsFlow.emit(
            ChatEvent.MessageRead(
                CONVERSATION_ID,
                "me-1",
                99L,
            ),
        )
        assertEquals(0L, conversationDao.rows[CONVERSATION_ID]?.peerReadSeq)

        // The peer's watermark persists monotonically — stale replays can't rewind it.
        eventsFlow.emit(
            ChatEvent.MessageRead(
                CONVERSATION_ID,
                "u-other",
                42L,
            ),
        )
        assertEquals(42L, conversationDao.rows[CONVERSATION_ID]?.peerReadSeq)

        eventsFlow.emit(
            ChatEvent.MessageRead(
                CONVERSATION_ID,
                "u-other",
                10L,
            ),
        )
        assertEquals(42L, conversationDao.rows[CONVERSATION_ID]?.peerReadSeq)
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
                replyToSenderName = null,
                replyToSnippet = null,
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
        val attachment = AttachmentDto("att-1", "pic.png", "image/png")
        var captured: SendMessageRequest? = null
        every { session.sendMessage(any(), any()) } answers {
            captured = secondArg()
            true
        }

        repository.send(
            CONVERSATION_ID,
            "pic.png",
            null,
            "IMAGE",
            listOf(attachment),
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
                    replyToSenderName = null, replyToSnippet = null,
                    createdAt = seq.toLong(), attachmentsJson = null,
                ),
            )
        }
        every {
            messageApi.getMessages(CONVERSATION_ID, null, 100L, MessageApi.DEFAULT_PAGE_SIZE)
        } returns Response.success(
            MessagePageDto(
                listOf(
                    messageDto("m-101", null, seq = 101),
                    messageDto("m-102", null, seq = 102),
                    messageDto("m-103", null, seq = 103),
                ),
                true,
            ),
        ).asCall()
        every {
            messageApi.getMessages(CONVERSATION_ID, null, 103L, MessageApi.DEFAULT_PAGE_SIZE)
        } returns Response.success(
            MessagePageDto(
                listOf(
                    messageDto("m-104", null, seq = 104),
                    messageDto("m-105", null, seq = 105),
                ),
                false,
            ),
        ).asCall()

        repository.resyncForward(CONVERSATION_ID)

        // All five missed rows landed — the gap is closed, not just the newest page.
        assertEquals(105, messageDao.rows.size)
        verify {
            messageApi.getMessages(
                CONVERSATION_ID,
                null,
                103L,
                MessageApi.DEFAULT_PAGE_SIZE,
            )
        }
    }

    @Test
    fun `openConversation forward-fills pushes missed while the topic was unwatched`() = runTest {
        // Room holds seq 1..3; pushes 4..5 arrived on the user queue while the
        // socket was dead, so Room never stored them. Opening the chat must
        // REST-fetch forward, not wait for the next reconnect.
        (1..3).forEach { seq ->
            messageDao.upsert(
                MessageEntity(
                    id = "m-$seq", conversationId = CONVERSATION_ID, seq = seq.toLong(),
                    senderId = "u-other", senderDisplayName = null, type = "TEXT",
                    content = "old", clientMessageId = null, replyToId = null,
                    replyToSenderName = null, replyToSnippet = null,
                    createdAt = seq.toLong(), attachmentsJson = null,
                ),
            )
        }
        every {
            messageApi.getMessages(CONVERSATION_ID, null, 3L, MessageApi.DEFAULT_PAGE_SIZE)
        } returns Response.success(
            MessagePageDto(
                listOf(
                    messageDto("m-4", null, seq = 4),
                    messageDto("m-5", null, seq = 5),
                ),
                false,
            ),
        ).asCall()
        every { messageApi.getPins(CONVERSATION_ID) } returns
            Response.success("[]".toResponseBody()).asCall()

        repository.openConversation(CONVERSATION_ID)

        assertEquals(5, messageDao.rows.size)
        verify {
            messageApi.getMessages(
                CONVERSATION_ID,
                null,
                3L,
                MessageApi.DEFAULT_PAGE_SIZE,
            )
        }
        // The read watermark covers the freshly fetched rows too.
        verify { messageApi.markRead(CONVERSATION_ID, any()) }
    }

    // ---- phase-10 interactions ----

    @Test
    fun `REACTION_UPDATED replaces the stored summary for the message`() = runTest {
        eventsFlow.emit(
            ChatEvent.ReactionUpdated(
                CONVERSATION_ID,
                "m-1",
                listOf(
                    ReactionSummaryDto("👍", 2, listOf("me-1", "u-2")),
                    ReactionSummaryDto("❤️", 1, listOf("u-3")),
                ),
            ),
        )

        val like = reactionDao.rows["m-1" to "👍"]
        assertEquals(2, like?.count)
        assertEquals(true, like?.reactedByMe)
        val heart = reactionDao.rows["m-1" to "❤️"]
        assertEquals(1, heart?.count)
        assertEquals(false, heart?.reactedByMe)

        // A second event replaces the whole set — stale emojis are gone.
        eventsFlow.emit(
            ChatEvent.ReactionUpdated(
                CONVERSATION_ID,
                "m-1",
                listOf(ReactionSummaryDto("🔥", 1)),
            ),
        )
        assertEquals(
            setOf("🔥"),
            reactionDao.rows.keys.filter { it.first == "m-1" }.map { it.second }.toSet(),
        )
    }

    @Test
    fun `PIN_UPDATED pins then unpins the message in pinsFlow`() = runTest {
        messageDao.upsert(
            MessageEntity(
                id = "m-9", conversationId = CONVERSATION_ID, seq = 9,
                senderId = "u-other", senderDisplayName = "Lan", type = "TEXT",
                content = "pin me", clientMessageId = null, replyToId = null,
                replyToSenderName = null, replyToSnippet = null,
                createdAt = 9L, attachmentsJson = null,
            ),
        )

        repository.pinsFlow(CONVERSATION_ID).test {
            assertTrue(awaitItem().isEmpty())

            eventsFlow.emit(ChatEvent.PinUpdated(CONVERSATION_ID, "m-9", true))
            val pinned = awaitItem()
            assertEquals(listOf("m-9"), pinned.map { it.id })
            assertEquals("pin me", pinned.single().content)

            eventsFlow.emit(ChatEvent.PinUpdated(CONVERSATION_ID, "m-9", false))
            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `toggleReaction writes optimistically then applies the server summary`() = runTest {
        var optimisticAtCall: MessageReactionEntity? = null
        var captured: ReactionRequest? = null
        every { messageApi.toggleReaction("m-1", any()) } answers {
            optimisticAtCall = reactionDao.rows["m-1" to "👍"]
            captured = secondArg()
            Response.success(
                ReactionUpdateDto(
                    "m-1",
                    listOf(ReactionSummaryDto("👍", 5, true)),
                ),
            ).asCall()
        }

        repository.toggleReaction("m-1", CONVERSATION_ID, "👍")

        // Optimistic write landed BEFORE the REST response.
        assertEquals(1, optimisticAtCall?.count)
        assertEquals(true, optimisticAtCall?.reactedByMe)
        assertEquals("👍", captured?.emoji)
        // …then the authoritative summary reconciled it.
        val final = reactionDao.rows["m-1" to "👍"]
        assertEquals(5, final?.count)
        assertEquals(true, final?.reactedByMe)
    }

    @Test
    fun `failed toggleReaction restores the prior summary and signals an error`() = runTest {
        reactionDao.upsertAll(
            listOf(
                MessageReactionEntity(
                    messageId = "m-1",
                    conversationId = CONVERSATION_ID,
                    emoji = "👍",
                    count = 3,
                    reactedByMe = false,
                ),
            ),
        )
        every { messageApi.toggleReaction(any(), any()) } returns
            Response.error<ReactionUpdateDto>(403, "".toResponseBody()).asCall()

        repository.interactionEvents.test {
            repository.toggleReaction("m-1", CONVERSATION_ID, "👍")

            val restored = reactionDao.rows["m-1" to "👍"]
            assertEquals(3, restored?.count)
            assertEquals(false, restored?.reactedByMe)
            assertTrue(awaitItem() is ApiError.Unknown)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `history refresh stores embedded reactions and the reply preview`() = runTest {
        val replyDto = MessageDto(
            "m-2",
            2L,
            CONVERSATION_ID,
            "u-other",
            "TEXT",
            "answer",
            ReplyRefDto("m-1", "Lan", "question"),
            listOf(ReactionSummaryDto("👍", 2, false)),
            "2026-11-03T09:12:04Z",
        )
        every { messageApi.getMessages(CONVERSATION_ID, null, null, any()) } returns
            Response.success(MessagePageDto(listOf(replyDto), false)).asCall()

        repository.refreshHistory(CONVERSATION_ID)

        assertEquals("Lan", messageDao.rows["m-2"]?.replyToSenderName)
        assertEquals("question", messageDao.rows["m-2"]?.replyToSnippet)
        val chip = reactionDao.rows["m-2" to "👍"]
        assertEquals(2, chip?.count)
        assertEquals(false, chip?.reactedByMe)
    }

    @Test
    fun `history distinguishes absent reactions from an explicit empty list`() = runTest {
        messageDao.upsert(storedMessage("m-1", seq = 1))
        val knownReaction = MessageReactionEntity("m-1", CONVERSATION_ID, "👍", 1, true)
        reactionDao.upsertAll(listOf(knownReaction))

        every { messageApi.getMessages(any(), any(), any(), any()) } returnsMany listOf(
            Response.success(
                page(
                    hasMore = false,
                    messageDto("m-1", null), // reactions absent: keep the current set
                ),
            ).asCall(),
            Response.success(
                page(
                    hasMore = false,
                    messageDto("m-1", null).withReactions(emptyList()),
                ),
            ).asCall(),
        )

        repository.refreshHistory(CONVERSATION_ID)
        assertEquals(listOf(knownReaction), reactionDao.forMessage("m-1"))

        repository.refreshHistory(CONVERSATION_ID)
        assertTrue(reactionDao.forMessage("m-1").isEmpty())
    }

    @Test
    fun `refreshPins replaces the conversation pin set from the REST list`() = runTest {
        pinDao.upsert(PinnedMessageEntity(CONVERSATION_ID, "stale", 1L))
        every { messageApi.getPins(CONVERSATION_ID) } returns
            Response.success("[\"m-1\",\"m-2\"]".toResponseBody()).asCall()

        repository.refreshPins(CONVERSATION_ID)

        assertEquals(
            setOf("m-1", "m-2"),
            pinDao.rows.keys.filter { it.first == CONVERSATION_ID }.map { it.second }.toSet(),
        )
    }

    @Test
    fun `pin failure removes the optimistic row and signals an error`() = runTest {
        every { messageApi.pinMessage(CONVERSATION_ID, any()) } returns
            Response.error<ResponseBody>(409, "".toResponseBody()).asCall()

        repository.interactionEvents.test {
            repository.pinMessage(CONVERSATION_ID, "m-1")

            assertTrue(pinDao.rows.isEmpty())
            assertTrue(awaitItem() is ApiError.Unknown)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- edit / delete / search ----

    @Test
    fun `edit writes optimistically then applies the authoritative server row`() = runTest {
        messageDao.upsert(ownMessage("m-1", seq = 1, content = "old"))
        var captured: EditMessageRequest? = null
        every { messageApi.editMessage(CONVERSATION_ID, "m-1", any()) } answers {
            captured = thirdArg()
            Response.success(
                messageDto("m-1", null, senderId = "me-1").withContent(
                    "new",
                    "2026-11-03T10:00:00Z",
                ),
            ).asCall()
        }

        val result = repository.editMessage(CONVERSATION_ID, "m-1", "  new  ")

        assertTrue(result is Result.Success)
        assertEquals("new", captured?.content)
        val row = messageDao.rows.getValue("m-1")
        assertEquals("new", row.content)
        assertEquals(isoToEpochMsOrNull("2026-11-03T10:00:00Z"), row.editedAtEpochMs)
    }

    @Test
    fun `failed edit restores the previous content and editedAt`() = runTest {
        messageDao.upsert(ownMessage("m-1", seq = 1, content = "old"))
        every { messageApi.editMessage(any(), any(), any()) } returns
            Response.error<MessageDto>(403, "".toResponseBody()).asCall()

        val result = repository.editMessage(CONVERSATION_ID, "m-1", "new")

        assertTrue(result is Result.Failure)
        val row = messageDao.rows.getValue("m-1")
        assertEquals("old", row.content)
        assertEquals(null, row.editedAtEpochMs)
    }

    @Test
    fun `edit response cannot restore an inbox preview after MESSAGE_DELETED`() = runTest {
        messageDao.upsert(ownMessage("m-1", seq = 1, content = "old"))
        conversationDao.upsert(conversation(lastMessageSeq = 1, content = "old"))
        every { messageApi.editMessage(CONVERSATION_ID, "m-1", any()) } answers {
            Response.success(
                messageDto("m-1", null, senderId = "me-1").withContent(
                    "edited",
                    "2026-11-03T10:00:00Z",
                ),
            ).asCall {
                eventsFlow.tryEmit(
                    ChatEvent.MessageDeleted(
                        CONVERSATION_ID,
                        "m-1",
                        "2026-11-03T11:00:00Z",
                    ),
                )
            }
        }

        repository.editMessage(CONVERSATION_ID, "m-1", "edited")

        assertTrue(messageDao.rows.getValue("m-1").deletedAtEpochMs != null)
        assertEquals(null, conversationDao.rows.getValue(CONVERSATION_ID).lastMessageContent)
        assertEquals(true, conversationDao.rows.getValue(CONVERSATION_ID).lastMessageDeleted)
    }

    @Test
    fun `cancelling an edit rolls back its optimistic row`() = runTest {
        messageDao.upsert(ownMessage("m-1", seq = 1, content = "old"))
        every { messageApi.editMessage(any(), any(), any()) } returns
            failedCall(CancellationException("test cancellation"))

        try {
            repository.editMessage(CONVERSATION_ID, "m-1", "edited")
            org.junit.Assert.fail("expected cancellation")
        } catch (_: CancellationException) {
            // expected
        }

        assertEquals("old", messageDao.rows.getValue("m-1").content)
        assertEquals(null, messageDao.rows.getValue("m-1").editedAtEpochMs)
    }

    @Test
    fun `edit ignores blank, identical and non-owned content`() = runTest {
        messageDao.upsert(ownMessage("m-1", seq = 1, content = "same"))
        messageDao.upsert(storedMessage("m-2", seq = 2)) // peer message

        assertTrue(repository.editMessage(CONVERSATION_ID, "m-1", "   ") is Result.Failure)
        assertTrue(repository.editMessage(CONVERSATION_ID, "m-1", "same") is Result.Success)
        assertTrue(repository.editMessage(CONVERSATION_ID, "m-2", "mine now") is Result.Failure)

        verify(exactly = 0) { messageApi.editMessage(any(), any(), any()) }
    }

    @Test
    fun `MESSAGE_UPDATED applies once and duplicate broadcasts are idempotent`() = runTest {
        messageDao.upsert(ownMessage("m-1", seq = 1, content = "old"))
        val event = ChatEvent.MessageUpdated(
            messageDto("m-1", null, senderId = "me-1").withContent(
                "edited",
                "2026-11-03T10:00:00Z",
            ),
            CONVERSATION_ID,
            null,
        )

        eventsFlow.emit(event)
        eventsFlow.emit(event)

        val row = messageDao.rows.getValue("m-1")
        assertEquals("edited", row.content)
        assertEquals(isoToEpochMsOrNull("2026-11-03T10:00:00Z"), row.editedAtEpochMs)
    }

    @Test
    fun `MESSAGE_DELETED tombstones the row, drops reactions and the pin`() = runTest {
        messageDao.upsert(ownMessage("m-1", seq = 1, content = "bye"))
        reactionDao.upsertAll(
            listOf(MessageReactionEntity("m-1", CONVERSATION_ID, "👍", 1, true)),
        )
        pinDao.upsert(PinnedMessageEntity(CONVERSATION_ID, "m-1", 1L))

        eventsFlow.emit(
            ChatEvent.MessageDeleted(
                CONVERSATION_ID,
                "m-1",
                "2026-11-03T11:00:00Z",
            ),
        )

        val row = messageDao.rows.getValue("m-1")
        assertEquals("", row.content)
        assertEquals(null, row.attachmentsJson)
        assertEquals(isoToEpochMsOrNull("2026-11-03T11:00:00Z"), row.deletedAtEpochMs)
        assertTrue(reactionDao.rows.isEmpty())
        assertTrue(pinDao.rows.isEmpty())

        // A replayed broadcast must keep the FIRST deletion timestamp.
        eventsFlow.emit(
            ChatEvent.MessageDeleted(
                CONVERSATION_ID,
                "m-1",
                "2026-11-04T11:00:00Z",
            ),
        )
        assertEquals(
            isoToEpochMsOrNull("2026-11-03T11:00:00Z"),
            messageDao.rows.getValue("m-1").deletedAtEpochMs,
        )
    }

    @Test
    fun `history tombstones clear pins reactions replies and inbox preview`() = runTest {
        messageDao.upsert(ownMessage("m-1", seq = 1, content = "bye"))
        messageDao.upsert(storedMessage("m-2", seq = 2).copy(
            replyToId = "m-1",
            replyToSnippet = "bye",
        ))
        reactionDao.upsertAll(
            listOf(MessageReactionEntity("m-1", CONVERSATION_ID, "👍", 1, true)),
        )
        pinDao.upsert(PinnedMessageEntity(CONVERSATION_ID, "m-1", 1L))
        conversationDao.upsert(conversation(lastMessageSeq = 1, content = "bye"))
        every { messageApi.getMessages(any(), any(), any(), any()) } returns
            Response.success(
                page(
                    hasMore = false,
                    messageDto("m-1", null, senderId = "me-1").withDeletedAt(
                        "2026-11-03T11:00:00Z",
                        listOf(ReactionSummaryDto("👍", 1, true)),
                    ),
                ),
            ).asCall()

        repository.refreshHistory(CONVERSATION_ID)

        val row = messageDao.rows.getValue("m-1")
        assertEquals("", row.content)
        assertEquals(null, row.attachmentsJson)
        assertTrue(row.deletedAtEpochMs != null)
        assertTrue(reactionDao.rows.isEmpty())
        assertTrue(pinDao.rows.isEmpty())
        assertEquals(null, messageDao.rows.getValue("m-2").replyToSnippet)
        assertEquals(null, conversationDao.rows.getValue(CONVERSATION_ID).lastMessageContent)
        assertEquals(true, conversationDao.rows.getValue(CONVERSATION_ID).lastMessageDeleted)
    }

    @Test
    fun `delete optimistically tombstones and clears reactions and pin`() = runTest {
        messageDao.upsert(ownMessage("m-1", seq = 1, content = "bye"))
        messageDao.upsert(
            storedMessage("m-2", seq = 2).copy(replyToId = "m-1", replyToSnippet = "bye"),
        )
        reactionDao.upsertAll(
            listOf(MessageReactionEntity("m-1", CONVERSATION_ID, "👍", 1, true)),
        )
        pinDao.upsert(PinnedMessageEntity(CONVERSATION_ID, "m-1", 1L))
        every { messageApi.deleteMessage(CONVERSATION_ID, "m-1") } returns
            Response.success<Void>(204, null).asCall()

        val result = repository.deleteMessage(CONVERSATION_ID, "m-1")

        assertTrue(result is Result.Success)
        val row = messageDao.rows.getValue("m-1")
        assertEquals("", row.content)
        assertTrue(row.deletedAtEpochMs != null)
        assertTrue(reactionDao.rows.isEmpty())
        assertTrue(pinDao.rows.isEmpty())
        // A reply pointing at the tombstone falls back to "unavailable".
        assertEquals(null, messageDao.rows.getValue("m-2").replyToSnippet)
    }

    @Test
    fun `failed delete restores the row, reactions and pin`() = runTest {
        messageDao.upsert(ownMessage("m-1", seq = 1, content = "bye"))
        reactionDao.upsertAll(
            listOf(MessageReactionEntity("m-1", CONVERSATION_ID, "👍", 1, true)),
        )
        pinDao.upsert(PinnedMessageEntity(CONVERSATION_ID, "m-1", 1L))
        every { messageApi.deleteMessage(any(), any()) } returns
            Response.error<Void>(403, "".toResponseBody()).asCall()

        val result = repository.deleteMessage(CONVERSATION_ID, "m-1")

        assertTrue(result is Result.Failure)
        val row = messageDao.rows.getValue("m-1")
        assertEquals("bye", row.content)
        assertEquals(null, row.deletedAtEpochMs)
        assertEquals(1, reactionDao.rows.size)
        assertEquals(1, pinDao.rows.size)
    }

    @Test
    fun `delete failure cannot roll back a concurrent MESSAGE_DELETED event`() = runTest {
        messageDao.upsert(ownMessage("m-1", seq = 1, content = "bye"))
        reactionDao.upsertAll(
            listOf(MessageReactionEntity("m-1", CONVERSATION_ID, "👍", 1, true)),
        )
        pinDao.upsert(PinnedMessageEntity(CONVERSATION_ID, "m-1", 1L))
        conversationDao.upsert(conversation(lastMessageSeq = 1, content = "bye"))
        every { messageApi.deleteMessage(CONVERSATION_ID, "m-1") } answers {
            Response.error<Void>(500, "".toResponseBody()).asCall {
                eventsFlow.tryEmit(
                    ChatEvent.MessageDeleted(
                        CONVERSATION_ID,
                        "m-1",
                        "2026-11-03T11:00:00Z",
                    ),
                )
            }
        }

        val result = repository.deleteMessage(CONVERSATION_ID, "m-1")

        assertTrue(result is Result.Failure)
        assertTrue(messageDao.rows.getValue("m-1").deletedAtEpochMs != null)
        assertTrue(reactionDao.rows.isEmpty())
        assertTrue(pinDao.rows.isEmpty())
        assertEquals(null, conversationDao.rows.getValue(CONVERSATION_ID).lastMessageContent)
        assertEquals(true, conversationDao.rows.getValue(CONVERSATION_ID).lastMessageDeleted)
    }

    @Test
    fun `cancelling a delete restores its optimistic state`() = runTest {
        messageDao.upsert(ownMessage("m-1", seq = 1, content = "bye"))
        reactionDao.upsertAll(
            listOf(MessageReactionEntity("m-1", CONVERSATION_ID, "👍", 1, true)),
        )
        pinDao.upsert(PinnedMessageEntity(CONVERSATION_ID, "m-1", 1L))
        conversationDao.upsert(conversation(lastMessageSeq = 1, content = "bye"))
        every { messageApi.deleteMessage(any(), any()) } returns
            failedCall(CancellationException("test cancellation"))

        try {
            repository.deleteMessage(CONVERSATION_ID, "m-1")
            org.junit.Assert.fail("expected cancellation")
        } catch (_: CancellationException) {
            // expected
        }

        assertEquals("bye", messageDao.rows.getValue("m-1").content)
        assertEquals(null, messageDao.rows.getValue("m-1").deletedAtEpochMs)
        assertEquals(1, reactionDao.rows.size)
        assertEquals(1, pinDao.rows.size)
        assertEquals("bye", conversationDao.rows.getValue(CONVERSATION_ID).lastMessageContent)
        assertEquals(false, conversationDao.rows.getValue(CONVERSATION_ID).lastMessageDeleted)
    }

    @Test
    fun `deleting the inbox-last message flips the neutral snippet flag`() = runTest {
        messageDao.upsert(ownMessage("m-1", seq = 1, content = "bye"))
        conversationDao.upsert(conversation(lastMessageSeq = 1, content = "bye"))
        every { messageApi.deleteMessage(any(), any()) } returns Response.success<Void>(204, null).asCall()

        repository.deleteMessage(CONVERSATION_ID, "m-1")

        val row = conversationDao.rows.getValue(CONVERSATION_ID)
        assertEquals(true, row.lastMessageDeleted)
        assertEquals(null, row.lastMessageContent)
    }

    @Test
    fun `deleting an already deleted message is an idempotent no-op`() = runTest {
        messageDao.upsert(
            ownMessage("m-1", seq = 1, content = "").copy(deletedAtEpochMs = 5L),
        )

        val result = repository.deleteMessage(CONVERSATION_ID, "m-1")

        assertTrue(result is Result.Success)
        verify(exactly = 0) { messageApi.deleteMessage(any(), any()) }
    }

    @Test
    fun `search writes through to Room and hydrates the page`() = runTest {
        every { messageApi.searchMessages(CONVERSATION_ID, "hi", any(), any()) } returns
            Response.success(
                MessagePageDto(
                    listOf(messageDto("m-9", null, seq = 9).withContent("hi there")),
                    9L,
                    true,
                ),
            ).asCall()

        val result = repository.searchMessages(CONVERSATION_ID, "  hi  ")

        val page = (result as Result.Success).value
        assertEquals(listOf("m-9"), page.items.map { it.id })
        assertEquals("hi there", page.items.single().content)
        assertTrue(page.hasMore)
        assertEquals(9L, page.nextBeforeSeq)
        assertEquals("hi there", messageDao.rows.getValue("m-9").content)
    }

    @Test
    fun `search below the two-character floor never calls the API`() = runTest {
        val result = repository.searchMessages(CONVERSATION_ID, " h ")

        assertTrue(result is Result.Success)
        assertTrue((result as Result.Success).value.items.isEmpty())
        verify(exactly = 0) { messageApi.searchMessages(any(), any(), any(), any()) }
    }

    @Test
    fun `timestamp conversion accepts fractional offset instants and rejects invalid input`() {
        val expected = Instant.parse("2026-11-03T10:00:00.123456789Z").toEpochMilli()

        assertEquals(expected, isoToEpochMsOrNull("2026-11-03T12:00:00.123456789+02:00"))
        assertEquals(null, isoToEpochMsOrNull("not-a-timestamp"))
    }

    // ---- jump to a message outside the loaded window ----

    @Test
    fun `loadUntilLoaded returns immediately for an already stored message`() = runTest {
        messageDao.upsert(storedMessage("m-9", seq = 9))

        assertEquals(MessageJump.Found.INSTANCE, repository.loadUntilLoaded(CONVERSATION_ID, "m-9"))
        verify(exactly = 0) { messageApi.getMessages(any(), any(), any(), any()) }
    }

    @Test
    fun `loadUntilLoaded pages backwards until the target is stored`() = runTest {
        messageDao.upsert(storedMessage("m-90", seq = 90))
        every { messageApi.getMessages(any(), any(), any(), any()) } returnsMany listOf(
            Response.success(page(hasMore = true, dto(storedMessage("m-45", 45)))).asCall(),
            Response.success(page(hasMore = true, dto(storedMessage("m-10", 10)))).asCall(),
            Response.success(page(hasMore = true, dto(storedMessage("m-2", 2)))).asCall(),
        )

        val jump = repository.loadUntilLoaded(CONVERSATION_ID, "m-2")

        assertEquals(MessageJump.Found.INSTANCE, jump)
        assertTrue(messageDao.rows.containsKey("m-2"))
        verify(exactly = 3) { messageApi.getMessages(any(), any(), any(), any()) }
        // Each page asked for history strictly before the then-oldest row.
        verify(exactly = 1) { messageApi.getMessages(CONVERSATION_ID, 45L, any(), any()) }
        verify(exactly = 1) { messageApi.getMessages(CONVERSATION_ID, 10L, any(), any()) }
    }

    @Test
    fun `loadUntilLoaded stops after the page budget and reports not found`() = runTest {
        messageDao.upsert(storedMessage("m-90", seq = 90))
        // Every page claims more history but never contains the target.
        every { messageApi.getMessages(any(), any(), any(), any()) } returns
            Response.success(page(hasMore = true, dto(storedMessage("m-x", seq = 1)))).asCall()

        val jump = repository.loadUntilLoaded(CONVERSATION_ID, "m-gone", 3)

        assertEquals(MessageJump.NotFound.INSTANCE, jump)
        verify(exactly = 3) { messageApi.getMessages(any(), any(), any(), any()) }
    }

    @Test
    fun `loadUntilLoaded stops when the server reports no older history`() = runTest {
        messageDao.upsert(storedMessage("m-5", seq = 5))
        every { messageApi.getMessages(any(), any(), any(), any()) } returns
            Response.success(page(hasMore = false)).asCall()

        assertEquals(MessageJump.NotFound.INSTANCE, repository.loadUntilLoaded(CONVERSATION_ID, "m-old"))
        verify(exactly = 1) { messageApi.getMessages(any(), any(), any(), any()) }
    }

    @Test
    fun `loadUntilLoaded surfaces a page failure`() = runTest {
        messageDao.upsert(storedMessage("m-5", seq = 5))
        every { messageApi.getMessages(any(), any(), any(), any()) } returns
            failedCall(IOException("down"))

        val jump = repository.loadUntilLoaded(CONVERSATION_ID, "m-old")

        assertTrue(jump is MessageJump.Failed)
        assertTrue((jump as MessageJump.Failed).error is ApiError.Network)
    }

    private fun storedMessage(id: String, seq: Long) = MessageEntity(
        id = id,
        conversationId = CONVERSATION_ID,
        seq = seq,
        senderId = "u-other",
        senderDisplayName = "Lan",
        type = "TEXT",
        content = id,
        clientMessageId = null,
        replyToId = null,
        replyToSenderName = null,
        replyToSnippet = null,
        createdAt = seq,
        attachmentsJson = null,
    )

    private fun ownMessage(id: String, seq: Long, content: String) = MessageEntity(
        id = id,
        conversationId = CONVERSATION_ID,
        seq = seq,
        senderId = "me-1",
        senderDisplayName = "Me",
        type = "TEXT",
        content = content,
        clientMessageId = null,
        replyToId = null,
        replyToSenderName = null,
        replyToSnippet = null,
        createdAt = seq,
        attachmentsJson = null,
    )

    private fun conversation(lastMessageSeq: Long, content: String?) = ConversationEntity(
        id = CONVERSATION_ID,
        type = "DIRECT",
        title = null,
        lastMessageSeq = lastMessageSeq,
        lastMessageContent = content,
        lastMessageSenderName = "Me",
        lastMessageAt = lastMessageSeq,
        unreadCount = 0,
        otherUserName = "Lan",
        otherUserAvatar = null,
        avatarUrl = null,
    )

    private fun dto(entity: MessageEntity) = MessageDto(
        entity.id,
        entity.seq,
        entity.conversationId,
        entity.senderId,
        entity.type,
        entity.content,
        "2026-11-03T09:12:04Z",
    )

    private fun page(hasMore: Boolean, vararg items: MessageDto) =
        MessagePageDto(items.toList(), hasMore)

    private companion object {
        const val CONVERSATION_ID = "c-1"
    }
}
