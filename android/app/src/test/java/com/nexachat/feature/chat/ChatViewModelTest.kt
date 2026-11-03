package com.nexachat.feature.chat

import androidx.lifecycle.SavedStateHandle
import com.nexachat.MainDispatcherRule
import com.nexachat.core.network.dto.MessagePageDto
import com.nexachat.core.network.stomp.RealtimeSession
import com.nexachat.core.common.Result
import com.nexachat.data.ChatMessage
import com.nexachat.data.ConversationRepository
import com.nexachat.data.ConversationSummary
import com.nexachat.data.DeliveryStatus
import com.nexachat.data.MessageRepository
import com.nexachat.data.TypingEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val messageRepository: MessageRepository = mockk()
    private val conversationRepository: ConversationRepository = mockk()
    private val session: RealtimeSession = mockk()

    private val messagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val conversationFlow = MutableStateFlow<ConversationSummary?>(null)
    private val typingFlow = MutableSharedFlow<TypingEvent>(extraBufferCapacity = 8)
    private val isOpenFlow = MutableStateFlow(true)

    private lateinit var viewModel: ChatViewModel

    private companion object {
        const val CONVERSATION_ID = "c-1"
    }

    private fun message(
        id: String,
        seq: Long?,
        mine: Boolean,
        status: DeliveryStatus = DeliveryStatus.SENT,
        clientMessageId: String? = null,
    ) = ChatMessage(
        id = id,
        conversationId = CONVERSATION_ID,
        seq = seq,
        senderId = if (mine) "me-1" else "u-other",
        senderDisplayName = if (mine) "Me" else "Lan",
        type = "TEXT",
        content = "hello $id",
        replyToId = null,
        createdAtEpochMs = 1_700_000_000_000L,
        clientMessageId = clientMessageId,
        status = status,
        mine = mine,
    )

    @Before
    fun setUp() {
        every { messageRepository.messagesFlow(any()) } returns messagesFlow
        every { messageRepository.typingEvents } returns typingFlow
        every { messageRepository.closeConversation(any()) } just Runs
        coEvery { messageRepository.openConversation(any()) } returns Unit
        coEvery { messageRepository.refreshHistory(any(), any()) } returns
            Result.Success(MessagePageDto())
        coEvery { messageRepository.sendRead(any(), any()) } returns Unit
        coEvery { messageRepository.send(any(), any(), any()) } returns Unit
        coEvery { messageRepository.retry(any()) } returns Unit
        coEvery { messageRepository.isMine(any()) } answers { firstArg<String?>() == "me-1" }
        every { conversationRepository.conversationFlow(any()) } returns conversationFlow
        every { session.isOpen } returns isOpenFlow
        every { session.sendTyping(any()) } returns true

        viewModel = ChatViewModel(
            savedStateHandle = SavedStateHandle(mapOf("conversationId" to CONVERSATION_ID)),
            messageRepository = messageRepository,
            conversationRepository = conversationRepository,
            session = session,
        )
    }

    /**
     * [ChatViewModel.uiState] is `stateIn(WhileSubscribed)` — a live collector
     * is what keeps the upstream combine running. Then `.value` reads are
     * deterministic under the unconfined test dispatcher.
     */
    private fun kotlinx.coroutines.test.TestScope.observeUi() {
        backgroundScope.launch { viewModel.uiState.collect {} }
    }

    @Test
    fun `send clears input and delegates to the outbox pipeline`() = runTest {
        observeUi()
        runCurrent()

        viewModel.onInputChange("xin chào")
        assertEquals("xin chào", viewModel.uiState.value.input)

        viewModel.send()

        assertEquals("", viewModel.uiState.value.input)
        coVerify(exactly = 1) {
            messageRepository.send(CONVERSATION_ID, "xin chào", null)
        }
    }

    @Test
    fun `blank input never reaches the repository`() = runTest {
        viewModel.onInputChange("   ")

        viewModel.send()

        coVerify(exactly = 0) { messageRepository.send(any(), any(), any()) }
    }

    @Test
    fun `optimistic pending row renders as SENDING`() = runTest {
        observeUi()
        runCurrent()
        val pending = message(
            id = "cm-1",
            seq = null,
            mine = true,
            status = DeliveryStatus.SENDING,
            clientMessageId = "cm-1",
        )

        messagesFlow.value = listOf(pending)
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(1, state.messages.size)
        assertEquals(DeliveryStatus.SENDING, state.messages.single().status)
        assertEquals(1, state.sending.size)
    }

    @Test
    fun `MESSAGE_CREATED dedup shows one SENT row not a duplicate`() = runTest {
        observeUi()
        runCurrent()
        val pending = message(
            id = "cm-1",
            seq = null,
            mine = true,
            status = DeliveryStatus.SENDING,
            clientMessageId = "cm-1",
        )
        val acked = message(
            id = "m-9",
            seq = 9,
            mine = true,
            status = DeliveryStatus.SENT,
            clientMessageId = "cm-1",
        )

        messagesFlow.value = listOf(pending)
        runCurrent()
        assertEquals(DeliveryStatus.SENDING, viewModel.uiState.value.messages.single().status)

        // Repository merged the ack: pending row replaced by the stored one.
        messagesFlow.value = listOf(acked)
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(1, state.messages.size)
        assertEquals("m-9", state.messages.single().id)
        assertEquals(DeliveryStatus.SENT, state.messages.single().status)
        assertTrue(state.sending.isEmpty())
    }

    @Test
    fun `tapping a FAILED message requeues it`() = runTest {
        viewModel.retryFailed("cm-7")

        coVerify(exactly = 1) { messageRepository.retry("cm-7") }
    }

    @Test
    fun `peer typing shows then expires after the TTL`() = runTest {
        observeUi()
        runCurrent()

        typingFlow.emit(TypingEvent(CONVERSATION_ID, "u-other", "Lan"))
        runCurrent()
        assertTrue(viewModel.uiState.value.peerTyping)

        advanceTimeBy(ChatViewModel.PEER_TYPING_TTL_MS + 1)
        runCurrent()
        assertFalse(viewModel.uiState.value.peerTyping)
    }

    @Test
    fun `typing event for another conversation is ignored`() = runTest {
        observeUi()
        runCurrent()
        typingFlow.emit(TypingEvent("other-conv", "u-other", "Lan"))
        runCurrent()
        assertFalse(viewModel.uiState.value.peerTyping)
    }

    @Test
    fun `my own typing echo does not light the indicator`() = runTest {
        observeUi()
        runCurrent()
        typingFlow.emit(TypingEvent(CONVERSATION_ID, "me-1", "Me"))
        runCurrent()
        assertFalse(viewModel.uiState.value.peerTyping)
    }

    @Test
    fun `input changes throttle typing sends to one per interval`() = runTest {
        viewModel.onInputChange("a")
        viewModel.onInputChange("ab")
        viewModel.onInputChange("abc")

        verify(exactly = 1) { session.sendTyping(CONVERSATION_ID) }
    }

    @Test
    fun `incoming message while open marks it read`() = runTest {
        runCurrent()
        messagesFlow.value = listOf(message(id = "m-1", seq = 9, mine = false))
        runCurrent()

        coVerify(atLeast = 1) { messageRepository.sendRead(CONVERSATION_ID, 9) }
    }

    @Test
    fun `my own message does not trigger a read call`() = runTest {
        runCurrent()
        messagesFlow.value = listOf(message(id = "m-2", seq = 10, mine = true))
        runCurrent()

        coVerify(exactly = 0) { messageRepository.sendRead(CONVERSATION_ID, 10) }
    }
}
