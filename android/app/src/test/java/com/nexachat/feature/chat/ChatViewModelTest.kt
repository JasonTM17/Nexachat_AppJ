package com.nexachat.feature.chat

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.nexachat.MainDispatcherRule
import com.nexachat.core.common.ApiError
import com.nexachat.core.network.dto.AttachmentDto
import com.nexachat.core.network.dto.MessagePageDto
import com.nexachat.core.network.stomp.RealtimeSession
import com.nexachat.core.common.Result
import com.nexachat.data.AttachmentRepository
import com.nexachat.data.AttachmentUploadEvent
import com.nexachat.data.ChatMessage
import com.nexachat.data.ConversationRepository
import com.nexachat.data.ConversationSummary
import com.nexachat.data.DeliveryStatus
import com.nexachat.data.LocalAttachment
import com.nexachat.data.MessageJump
import com.nexachat.data.MessageRepository
import com.nexachat.data.MessageSearchResult
import com.nexachat.data.PeerPresence
import com.nexachat.data.TypingEvent
import com.nexachat.feature.media.PendingAttachment
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
    private val attachmentRepository: AttachmentRepository = mockk()
    private val session: RealtimeSession = mockk()

    private val messagesFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val conversationFlow = MutableStateFlow<ConversationSummary?>(null)
    private val typingFlow = MutableSharedFlow<TypingEvent>(extraBufferCapacity = 8)
    private val isOpenFlow = MutableStateFlow(true)
    private val pinsFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val interactionFlow = MutableSharedFlow<ApiError>(extraBufferCapacity = 8)

    private lateinit var viewModel: ChatViewModel

    private companion object {
        const val CONVERSATION_ID = "c-1"
        const val TARGET_CONVERSATION_ID = "c-target"
        val ATTACHMENT = AttachmentDto("att-1", "pic.png", "image/png", 12L)
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
        replyToSenderName = null,
        replyToSnippet = null,
        createdAtEpochMs = 1_700_000_000_000L,
        clientMessageId = clientMessageId,
        status = status,
        mine = mine,
    )

    @Before
    fun setUp() {
        every { messageRepository.messagesFlow(any()) } returns messagesFlow
        every { messageRepository.typingEvents } returns typingFlow
        every { messageRepository.pinsFlow(any()) } returns pinsFlow
        every { messageRepository.interactionEvents } returns interactionFlow
        every { messageRepository.closeConversation(any()) } just Runs
        coEvery { messageRepository.openConversation(any()) } returns Unit
        coEvery { messageRepository.refreshHistory(any(), any()) } returns
            Result.Success(MessagePageDto())
        coEvery { messageRepository.sendRead(any(), any()) } returns Unit
        coEvery { messageRepository.send(any(), any(), any(), any(), any()) } returns Unit
        coEvery { messageRepository.retry(any()) } returns Unit
        coEvery { messageRepository.toggleReaction(any(), any(), any()) } returns Unit
        coEvery { messageRepository.pinMessage(any(), any()) } returns Unit
        coEvery { messageRepository.unpinMessage(any(), any()) } returns Unit
        coEvery { attachmentRepository.describe(any()) } answers {
            LocalAttachment(
                uri = firstArg(),
                displayName = "pic.png",
                mimeType = "image/png",
                sizeBytes = 12L,
                type = "IMAGE",
            )
        }
        every { attachmentRepository.upload(any(), any()) } returns
            kotlinx.coroutines.flow.flowOf(
                AttachmentUploadEvent.Progress(0.4f),
                AttachmentUploadEvent.Uploaded(ATTACHMENT),
            )
        coEvery { messageRepository.isMine(any()) } answers { firstArg<String?>() == "me-1" }
        every { conversationRepository.conversationFlow(any()) } returns conversationFlow
        every { session.isOpen } returns isOpenFlow
        every { session.sendTyping(any()) } returns true

        viewModel = ChatViewModel(
            savedStateHandle = SavedStateHandle(mapOf("conversationId" to CONVERSATION_ID)),
            messageRepository = messageRepository,
            conversationRepository = conversationRepository,
            attachmentRepository = attachmentRepository,
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

    /**
     * The draft rides SavedStateHandle, so a process-death restore (a new VM
     * built over the same handle) rehydrates it — rotation alone never needed
     * this because the VM survives.
     */
    @Test
    fun `draft input is restored from SavedStateHandle`() = runTest {
        val restored = ChatViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf(
                    "conversationId" to CONVERSATION_ID,
                    "chat_input" to "nháp tay",
                ),
            ),
            messageRepository = messageRepository,
            conversationRepository = conversationRepository,
            attachmentRepository = attachmentRepository,
            session = session,
        )
        backgroundScope.launch { restored.uiState.collect {} }
        runCurrent()

        assertEquals("nháp tay", restored.uiState.value.input)
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

    // ---- attachments (phase-09) ----

    private val uri: Uri = mockk()

    @Test
    fun `sendAttachments stages a pending row then sends with attachmentIds`() = runTest {
        observeUi()
        runCurrent()

        viewModel.sendAttachments(listOf(uri))
        runCurrent()

        coVerify(exactly = 1) {
            messageRepository.send(
                conversationId = CONVERSATION_ID,
                content = "pic.png",
                replyToId = null,
                type = "IMAGE",
                attachments = listOf(ATTACHMENT),
            )
        }
        // Upload finished — the strip row is gone.
        assertTrue(viewModel.uiState.value.pendingAttachments.isEmpty())
    }

    @Test
    fun `server sniffed video MIME wins when picker cannot identify the URI`() = runTest {
        val video = AttachmentDto("att-video", "clip.mp4", "video/mp4", 4L)
        coEvery { attachmentRepository.describe(uri) } returns LocalAttachment(
            uri = uri,
            displayName = "clip.mp4",
            mimeType = null,
            sizeBytes = 4L,
            type = "FILE",
        )
        every { attachmentRepository.upload(uri, null) } returns
            kotlinx.coroutines.flow.flowOf(AttachmentUploadEvent.Uploaded(video))
        observeUi()
        runCurrent()

        viewModel.sendAttachments(listOf(uri))
        runCurrent()

        coVerify(exactly = 1) {
            messageRepository.send(
                conversationId = CONVERSATION_ID,
                content = "clip.mp4",
                replyToId = null,
                type = "VIDEO",
                attachments = listOf(video),
            )
        }
    }

    @Test
    fun `in-flight upload shows a pending row with progress`() = runTest {
        every { attachmentRepository.upload(any(), any()) } returns
            kotlinx.coroutines.flow.flow {
                emit(AttachmentUploadEvent.Progress(0.3f))
                kotlinx.coroutines.awaitCancellation()
            }
        observeUi()
        runCurrent()

        viewModel.sendAttachments(listOf(uri))
        runCurrent()

        val row = viewModel.uiState.value.pendingAttachments.single()
        assertEquals(PendingAttachment.PendingState.UPLOADING, row.state)
        assertEquals(0.3f, row.progress, 0.0001f)
        assertEquals("pic.png", row.displayName)
        coVerify(exactly = 0) {
            messageRepository.send(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `failed upload marks the row FAILED and never sends`() = runTest {
        every { attachmentRepository.upload(any(), any()) } returns
            kotlinx.coroutines.flow.flowOf(
                AttachmentUploadEvent.Progress(0.2f),
                AttachmentUploadEvent.Failed(ApiError.Network("down")),
            )
        observeUi()
        runCurrent()

        viewModel.sendAttachments(listOf(uri))
        runCurrent()

        val row = viewModel.uiState.value.pendingAttachments.single()
        assertEquals(PendingAttachment.PendingState.FAILED, row.state)
        coVerify(exactly = 0) {
            messageRepository.send(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `remove-X drops the pending row`() = runTest {
        every { attachmentRepository.upload(any(), any()) } returns
            kotlinx.coroutines.flow.flow {
                emit(AttachmentUploadEvent.Progress(0.1f))
                kotlinx.coroutines.awaitCancellation()
            }
        observeUi()
        runCurrent()

        viewModel.sendAttachments(listOf(uri))
        runCurrent()
        val row = viewModel.uiState.value.pendingAttachments.single()

        viewModel.removePendingAttachment(row.localId)
        runCurrent()

        assertTrue(viewModel.uiState.value.pendingAttachments.isEmpty())
    }

    // ---- phase-10 interactions ----

    @Test
    fun `reply draft passes replyToId into send then clears`() = runTest {
        observeUi()
        runCurrent()

        viewModel.setReplyTarget(message(id = "m-5", seq = 5, mine = false))
        runCurrent()
        assertEquals("m-5", viewModel.uiState.value.replyTarget?.messageId)

        viewModel.onInputChange("đồng ý")
        viewModel.send()
        runCurrent()

        coVerify(exactly = 1) {
            messageRepository.send(CONVERSATION_ID, "đồng ý", "m-5")
        }
        assertEquals(null, viewModel.uiState.value.replyTarget)
    }

    @Test
    fun `pending messages cannot become a reply target`() = runTest {
        observeUi()
        runCurrent()

        viewModel.setReplyTarget(
            message(id = "cm-1", seq = null, mine = true, status = DeliveryStatus.SENDING),
        )
        runCurrent()

        assertEquals(null, viewModel.uiState.value.replyTarget)
    }

    @Test
    fun `cancel clears the reply draft without sending`() = runTest {
        observeUi()
        runCurrent()

        viewModel.setReplyTarget(message(id = "m-5", seq = 5, mine = false))
        runCurrent()
        viewModel.clearReplyTarget()
        runCurrent()

        assertEquals(null, viewModel.uiState.value.replyTarget)
    }

    @Test
    fun `togglePin pins when unpinned and unpins when pinned`() = runTest {
        observeUi()
        runCurrent()
        val target = message(id = "m-1", seq = 1, mine = false)

        viewModel.togglePin(target)
        coVerify(exactly = 1) { messageRepository.pinMessage(CONVERSATION_ID, "m-1") }

        pinsFlow.value = listOf(target)
        runCurrent()

        viewModel.togglePin(target)
        coVerify(exactly = 1) { messageRepository.unpinMessage(CONVERSATION_ID, "m-1") }
    }

    @Test
    fun `toggleReaction delegates the emoji toggle to the repository`() = runTest {
        viewModel.toggleReaction(message(id = "m-3", seq = 3, mine = false), "👍")

        coVerify(exactly = 1) {
            messageRepository.toggleReaction("m-3", CONVERSATION_ID, "👍")
        }
    }

    @Test
    fun `pin failures surface in ui error state`() = runTest {
        observeUi()
        runCurrent()

        interactionFlow.emit(ApiError.Unknown("PIN_LIMIT"))
        runCurrent()

        assertEquals(ApiError.Unknown("PIN_LIMIT"), viewModel.uiState.value.error)
    }

    // ---- jump to a quoted / pinned message outside the loaded window ----

    @Test
    fun `jumpToMessage emits the scroll target once the message is loaded`() = runTest {
        coEvery { messageRepository.loadUntilLoaded(CONVERSATION_ID, "m-old", any()) } returns
            MessageJump.Found

        viewModel.jumpTargets.test {
            viewModel.jumpToMessage("m-old")
            assertEquals("m-old", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `jumpToMessage reports a target that is gone`() = runTest {
        coEvery { messageRepository.loadUntilLoaded(any(), any(), any()) } returns
            MessageJump.NotFound
        observeUi()
        runCurrent()

        viewModel.jumpToMessage("m-gone")
        runCurrent()

        assertEquals(
            ApiError.Unknown(ChatViewModel.ERROR_MESSAGE_GONE),
            viewModel.uiState.value.error,
        )
    }

    @Test
    fun `jumpToMessage surfaces a history page failure`() = runTest {
        coEvery { messageRepository.loadUntilLoaded(any(), any(), any()) } returns
            MessageJump.Failed(ApiError.Network("down"))
        observeUi()
        runCurrent()

        viewModel.jumpToMessage("m-old")
        runCurrent()

        assertEquals(ApiError.Network("down"), viewModel.uiState.value.error)
    }

    // ---- edit / delete / forward / search ----

    @Test
    fun `canEdit allows only my live delivered TEXT messages`() = runTest {
        assertTrue(viewModel.canEdit(message(id = "m-1", seq = 1, mine = true)))
        assertFalse(viewModel.canEdit(message(id = "m-2", seq = 2, mine = false)))
        assertFalse(
            viewModel.canEdit(
                message(id = "m-3", seq = 3, mine = true, status = DeliveryStatus.SENDING),
            ),
        )
        assertFalse(
            viewModel.canEdit(
                message(id = "m-4", seq = 4, mine = true).copy(deletedAtEpochMs = 1L),
            ),
        )
        assertFalse(
            viewModel.canEdit(
                message(id = "m-5", seq = 5, mine = true).copy(type = "IMAGE"),
            ),
        )
    }

    @Test
    fun `canDelete allows my live messages of any type`() = runTest {
        assertTrue(viewModel.canDelete(message(id = "m-1", seq = 1, mine = true)))
        assertTrue(
            viewModel.canDelete(
                message(id = "m-2", seq = 2, mine = true).copy(type = "IMAGE"),
            ),
        )
        assertFalse(viewModel.canDelete(message(id = "m-3", seq = 3, mine = false)))
        assertFalse(
            viewModel.canDelete(
                message(id = "m-4", seq = 4, mine = true)
                    .copy(deletedAtEpochMs = 1L),
            ),
        )
    }

    @Test
    fun `editMessage trims and delegates`() = runTest {
        coEvery { messageRepository.editMessage(any(), any(), any()) } returns Result.Success(Unit)

        viewModel.editMessage("m-1", "  new text  ")
        runCurrent()

        coVerify(exactly = 1) {
            messageRepository.editMessage(CONVERSATION_ID, "m-1", "new text")
        }
    }

    @Test
    fun `editMessage with blank input never calls the repository`() = runTest {
        viewModel.editMessage("m-1", "   ")
        runCurrent()

        coVerify(exactly = 0) { messageRepository.editMessage(any(), any(), any()) }
    }

    @Test
    fun `edit failure surfaces in the ui error state`() = runTest {
        observeUi()
        runCurrent()
        coEvery { messageRepository.editMessage(any(), any(), any()) } returns
            Result.failure(ApiError.Unknown("MESSAGE_DELETED"))

        viewModel.editMessage("m-1", "new")
        runCurrent()

        assertEquals(ApiError.Unknown("MESSAGE_DELETED"), viewModel.uiState.value.error)
    }

    @Test
    fun `edit success emits MessageEdited and failure does not`() = runTest {
        coEvery { messageRepository.editMessage(any(), any(), any()) } returns Result.Success(Unit)

        viewModel.actionEvents.test {
            viewModel.editMessage("m-1", "updated")
            runCurrent()
            assertEquals(ChatActionEvent.MessageEdited("m-1"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        coEvery { messageRepository.editMessage(any(), any(), any()) } returns
            Result.failure(ApiError.Unknown("NETWORK"))
        viewModel.actionEvents.test {
            viewModel.editMessage("m-1", "again")
            runCurrent()
            expectNoEvents()
        }
    }

    @Test
    fun `deleteMessage delegates and surfaces failures`() = runTest {
        observeUi()
        runCurrent()
        coEvery { messageRepository.deleteMessage(any(), any()) } returns Result.Success(Unit)

        viewModel.deleteMessage("m-1")
        runCurrent()
        coVerify(exactly = 1) { messageRepository.deleteMessage(CONVERSATION_ID, "m-1") }

        coEvery { messageRepository.deleteMessage(any(), any()) } returns
            Result.failure(ApiError.Unknown("NOT_MESSAGE_AUTHOR"))
        viewModel.deleteMessage("m-2")
        runCurrent()
        assertEquals(
            ApiError.Unknown("NOT_MESSAGE_AUTHOR"),
            viewModel.uiState.value.error,
        )
    }

    @Test
    fun `forward re-sends the content as a new TEXT message and emits an event`() = runTest {
        observeUi()
        runCurrent()
        messagesFlow.value = listOf(message(id = "m-1", seq = 1, mine = true))

        viewModel.actionEvents.test {
            viewModel.forwardMessage("m-1", TARGET_CONVERSATION_ID)
            runCurrent()

            assertEquals(
                ChatActionEvent.MessageForwarded(TARGET_CONVERSATION_ID),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
        coVerify(exactly = 1) {
            messageRepository.send(TARGET_CONVERSATION_ID, "hello m-1", null)
        }
    }

    @Test
    fun `forward skips deleted and pending messages`() = runTest {
        observeUi()
        runCurrent()
        messagesFlow.value = listOf(
            message(id = "m-del", seq = 1, mine = true).copy(deletedAtEpochMs = 1L),
            message(
                id = "cm-1",
                seq = null,
                mine = true,
                status = DeliveryStatus.SENDING,
                clientMessageId = "cm-1",
            ),
        )

        viewModel.forwardMessage("m-del", TARGET_CONVERSATION_ID)
        viewModel.forwardMessage("cm-1", TARGET_CONVERSATION_ID)
        runCurrent()

        coVerify(exactly = 0) { messageRepository.send(any(), any(), any()) }
    }

    @Test
    fun `search debounces keystrokes and queries the final term after 300 ms`() = runTest {
        coEvery { messageRepository.searchMessages(any(), any(), any(), any()) } returns
            Result.Success(MessageSearchResult(emptyList(), hasMore = false, nextBeforeSeq = null))

        viewModel.search("h")
        runCurrent()
        viewModel.search("hi")
        advanceTimeBy(ChatViewModel.SEARCH_DEBOUNCE_MS - 1)
        runCurrent()

        coVerify(exactly = 0) { messageRepository.searchMessages(any(), any(), any(), any()) }

        advanceTimeBy(1)
        runCurrent()

        coVerify(exactly = 1) {
            messageRepository.searchMessages(CONVERSATION_ID, "hi", null)
        }
    }

    @Test
    fun `search below two characters clears results without a call`() = runTest {
        coEvery { messageRepository.searchMessages(any(), any(), any(), any()) } returns
            Result.Success(MessageSearchResult(emptyList(), hasMore = false, nextBeforeSeq = null))
        viewModel.search("hello")
        advanceTimeBy(ChatViewModel.SEARCH_DEBOUNCE_MS + 1)
        runCurrent()

        viewModel.search("h")
        advanceTimeBy(ChatViewModel.SEARCH_DEBOUNCE_MS + 1)
        runCurrent()

        assertEquals("h", viewModel.searchState.value.query)
        assertTrue(viewModel.searchState.value.results.isEmpty())
        assertFalse(viewModel.searchState.value.loading)
        coVerify(exactly = 1) { messageRepository.searchMessages(any(), any(), any(), any()) }
    }

    @Test
    fun `search exposes results and pages with the server cursor`() = runTest {
        val first = listOf(
            message(id = "m-9", seq = 9, mine = true),
            message(id = "m-8", seq = 8, mine = false),
        )
        val second = listOf(message(id = "m-7", seq = 7, mine = true))
        coEvery {
            messageRepository.searchMessages(CONVERSATION_ID, "hi", null)
        } returns Result.Success(MessageSearchResult(first, hasMore = true, nextBeforeSeq = 8))
        coEvery {
            messageRepository.searchMessages(CONVERSATION_ID, "hi", 8L)
        } returns Result.Success(MessageSearchResult(second, hasMore = false, nextBeforeSeq = null))

        viewModel.search("hi")
        advanceTimeBy(ChatViewModel.SEARCH_DEBOUNCE_MS + 1)
        runCurrent()

        assertEquals(listOf("m-9", "m-8"), viewModel.searchState.value.results.map { it.id })
        assertTrue(viewModel.searchState.value.hasMore)

        viewModel.loadMoreSearch()
        runCurrent()

        assertEquals(
            listOf("m-9", "m-8", "m-7"),
            viewModel.searchState.value.results.map { it.id },
        )
        assertFalse(viewModel.searchState.value.hasMore)
    }

    @Test
    fun `clearSearch drops the query and results`() = runTest {
        coEvery { messageRepository.searchMessages(any(), any(), any(), any()) } returns
            Result.Success(
                MessageSearchResult(
                    listOf(message(id = "m-1", seq = 1, mine = true)),
                    hasMore = false,
                    nextBeforeSeq = null,
                ),
            )
        viewModel.search("hi")
        advanceTimeBy(ChatViewModel.SEARCH_DEBOUNCE_MS + 1)
        runCurrent()
        assertTrue(viewModel.searchState.value.results.isNotEmpty())

        viewModel.clearSearch()

        assertEquals(ChatSearchState(), viewModel.searchState.value)
    }

    @Test
    fun `search failure surfaces on the search state only`() = runTest {
        coEvery { messageRepository.searchMessages(any(), any(), any(), any()) } returns
            Result.failure(ApiError.Network("down"))

        viewModel.search("hi")
        advanceTimeBy(ChatViewModel.SEARCH_DEBOUNCE_MS + 1)
        runCurrent()

        assertEquals(ApiError.Network("down"), viewModel.searchState.value.error)
        assertFalse(viewModel.searchState.value.loading)
    }

    @Test
    fun `peer presence comes from the DIRECT conversation summary`() = runTest {
        observeUi()
        runCurrent()

        conversationFlow.value = ConversationSummary(
            id = CONVERSATION_ID,
            type = "DIRECT",
            title = null,
            lastMessageContent = null,
            lastMessageSenderName = null,
            lastMessageAtEpochMs = null,
            lastMessageSeq = 0L,
            unreadCount = 0,
            otherUserName = "Peer",
            otherUserAvatar = null,
            avatarUrl = null,
            peerPresence = PeerPresence(true, 1_700_000_000_000L),
        )
        runCurrent()

        assertEquals(true, viewModel.uiState.value.peerPresence?.online)
    }

    @Test
    fun `direct peer id is exposed before the first message`() = runTest {
        observeUi()
        runCurrent()

        conversationFlow.value = ConversationSummary(
            id = CONVERSATION_ID,
            type = "DIRECT",
            title = null,
            lastMessageContent = null,
            lastMessageSenderName = null,
            lastMessageAtEpochMs = null,
            lastMessageSeq = 0L,
            unreadCount = 0,
            otherUserName = "Peer",
            otherUserAvatar = null,
            avatarUrl = null,
            otherUserId = "u-peer",
        )
        runCurrent()

        assertTrue(messagesFlow.value.isEmpty())
        assertEquals("u-peer", viewModel.uiState.value.otherUserId)
    }

    @Test
    fun `canPin gates the pin affordance to group admins and direct members`() = runTest {
        observeUi()
        runCurrent()

        fun summary(type: String, role: String?) = ConversationSummary(
            id = CONVERSATION_ID,
            type = type,
            title = if (type == "GROUP") "Group" else null,
            lastMessageContent = null,
            lastMessageSenderName = null,
            lastMessageAtEpochMs = null,
            lastMessageSeq = 0L,
            unreadCount = 0,
            otherUserName = if (type == "GROUP") null else "Peer",
            otherUserAvatar = null,
            avatarUrl = null,
            peerReadSeq = 0L,
            myRole = role,
        )

        // A non-admin member must not be offered a doomed pin (server 403s it).
        conversationFlow.value = summary("GROUP", "MEMBER")
        runCurrent()
        assertEquals(false, viewModel.uiState.value.canPin)

        conversationFlow.value = summary("GROUP", "ADMIN")
        runCurrent()
        assertEquals(true, viewModel.uiState.value.canPin)

        // Unknown role (legacy payload without members[]) stays hidden.
        conversationFlow.value = summary("GROUP", null)
        runCurrent()
        assertEquals(false, viewModel.uiState.value.canPin)

        // DIRECT members always pin.
        conversationFlow.value = summary("DIRECT", null)
        runCurrent()
        assertEquals(true, viewModel.uiState.value.canPin)
    }
}
