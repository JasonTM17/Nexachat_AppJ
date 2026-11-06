package com.nexachat.data

import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.database.ConversationDao
import com.nexachat.core.database.MessageDao
import com.nexachat.core.database.MessageEntity
import com.nexachat.core.database.OutboxDao
import com.nexachat.core.database.PinDao
import com.nexachat.core.database.ReactionDao
import com.nexachat.core.network.MessageApi
import com.nexachat.core.network.SessionEvents
import com.nexachat.core.network.stomp.ChatEvent
import com.nexachat.core.network.stomp.RealtimeSession
import com.nexachat.core.storage.TokenStore
import com.nexachat.core.storage.Tokens
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Resilience path for the F1/F2 writes: with no reachable backend the
 * optimistic Room edit/delete must be rolled back and the caller must get a
 * typed transport error (no outbox, no silent success).
 *
 * The API here is a REAL Retrofit service pointing at a closed port, so the
 * failure is an actual connection error rather than a mocked exception.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageRepositoryOfflineTest {

    private companion object {
        const val CONVERSATION_ID = "c-offline"
        const val MESSAGE_ID = "m-offline"
        const val MY_ID = "me-1"
    }

    private val dispatcher = UnconfinedTestDispatcher()
    private val conversationDao: ConversationDao = mockk(relaxed = true)
    private val outboxDao: OutboxDao = mockk(relaxed = true)
    private val reactionDao: ReactionDao = mockk(relaxed = true)
    private val pinDao: PinDao = mockk(relaxed = true)
    private val session: RealtimeSession = mockk()
    private val tokenStore: TokenStore = mockk()
    private val sessionEvents: SessionEvents = mockk()
    private val moshi = Moshi.Builder().build()

    /** Standing row the fake DAO returns; updates replace it in place. */
    private var row: MessageEntity? = null
    private val messageDao: MessageDao = mockk()

    private lateinit var repository: MessageRepository

    @Before
    fun setUp() {
        row = MessageEntity(
            id = MESSAGE_ID,
            conversationId = CONVERSATION_ID,
            seq = 7,
            senderId = MY_ID,
            senderDisplayName = "Me",
            type = "TEXT",
            content = "original",
            clientMessageId = null,
            replyToId = null,
            replyToSenderName = null,
            replyToSnippet = null,
            createdAt = 1_700_000_000_000,
            attachmentsJson = null,
        )
        coEvery { messageDao.byId(any()) } answers { row }
        coEvery { messageDao.update(any()) } answers { row = firstArg() }
        coEvery { reactionDao.forMessage(any()) } returns emptyList()
        coEvery { pinDao.find(any(), any()) } returns null

        every { session.events } returns MutableSharedFlow<ChatEvent>(extraBufferCapacity = 4)
        every { session.isOpen } returns MutableStateFlow(true)
        every { session.resyncRequests } returns MutableSharedFlow<Unit>(extraBufferCapacity = 4)
        every { session.sendMessage(any(), any()) } returns true
        every { session.sendTyping(any()) } returns true
        every { session.watchConversation(any()) } just Runs
        every { session.unwatchConversation(any()) } just Runs
        every { session.ensureConnected() } just Runs
        every { sessionEvents.events } returns
            MutableSharedFlow<SessionEvents.Event>(extraBufferCapacity = 4)
        coEvery { tokenStore.readTokens() } returns
            Tokens(jwtFor(MY_ID), Long.MAX_VALUE, "refresh")

        repository = MessageRepository(
            messageApi = unreachableApi(),
            messageDao = messageDao,
            conversationDao = conversationDao,
            outboxDao = outboxDao,
            reactionDao = reactionDao,
            pinDao = pinDao,
            session = session,
            tokenStore = tokenStore,
            sessionEvents = sessionEvents,
            moshi = moshi,
            scope = CoroutineScope(dispatcher),
        )
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `edit against an unreachable backend reverts the row with a network error`() = runTest {
        val result = repository.editMessage(CONVERSATION_ID, MESSAGE_ID, "edited")

        assertTrue("expected a failure, got $result", result is Result.Failure)
        assertTrue(
            "expected ApiError.Network, got ${(result as Result.Failure).error}",
            result.error is ApiError.Network,
        )
        assertEquals("original", row?.content)
        assertNull("optimistic editedAt survived the rollback", row?.editedAtEpochMs)
    }

    @Test
    fun `delete against an unreachable backend reverts the tombstone`() = runTest {
        val result = repository.deleteMessage(CONVERSATION_ID, MESSAGE_ID)

        assertTrue("expected a failure, got $result", result is Result.Failure)
        assertTrue(
            "expected ApiError.Network, got ${(result as Result.Failure).error}",
            result.error is ApiError.Network,
        )
        assertEquals("original", row?.content)
        assertNull("optimistic tombstone survived the rollback", row?.deletedAtEpochMs)
    }

    /** A real Retrofit stack pointed at a port nothing listens on. */
    private fun unreachableApi(): MessageApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl("http://127.0.0.1:1/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(MessageApi::class.java)
    }

    private fun jwtFor(userId: String): String {
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"sub\":\"$userId\"}".toByteArray(Charsets.UTF_8))
        return "header.$payload.signature"
    }
}
