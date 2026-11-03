package com.nexachat.feature.chat

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.network.dto.AttachmentDto
import com.nexachat.core.network.stomp.RealtimeSession
import com.nexachat.data.AttachmentRepository
import com.nexachat.data.AttachmentUploadEvent
import com.nexachat.data.ChatMessage
import com.nexachat.data.ConversationRepository
import com.nexachat.data.DeliveryStatus
import com.nexachat.data.MessageRepository
import com.nexachat.feature.media.PendingAttachment
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs `chat/{conversationId}` (plan §12.2 UDF).
 *
 * All render state derives from flows: messages come from Room+outbox via
 * [MessageRepository.messagesFlow] (restart-safe — nothing lives only in
 * memory), the draft/input ([KEY_INPUT] — SavedStateHandle, so it survives
 * process death too) and per-screen flags are flows merged by [uiState]. A
 * rotation is pure recomposition: the VM survives and Room re-emits.
 *
 * Typing (plan §11.3.6): outbound signals are throttled to one SEND per
 * [TYPING_SEND_INTERVAL_MS] while text is non-blank; an inbound peer signal
 * shows the indicator for [PEER_TYPING_TTL_MS] unless refreshed.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val attachmentRepository: AttachmentRepository,
    private val session: RealtimeSession,
) : ViewModel() {

    private val conversationId: String =
        savedStateHandle.get<String>("conversationId").orEmpty()

    data class UiState(
        val loading: Boolean = true,
        /** Room messages (SENT) merged with pending outbox rows (SENDING/FAILED). */
        val messages: List<ChatMessage> = emptyList(),
        /** Pending-only view of [messages] — drives the retry affordance/tests. */
        val sending: List<ChatMessage> = emptyList(),
        val input: String = "",
        val peerTyping: Boolean = false,
        val peerName: String? = null,
        /** GROUP conversations render sender names above incoming bubbles. */
        val isGroup: Boolean = false,
        val connected: Boolean = true,
        val hasMore: Boolean = false,
        val error: ApiError? = null,
        /** In-flight uploads staged above the input bar (phase-09 preview strip). */
        val pendingAttachments: List<PendingAttachment> = emptyList(),
    )

    /**
     * Draft text — SavedStateHandle-backed so it survives process death, not
     * just the VM-retained config change.
     */
    private val input = savedStateHandle.getStateFlow(KEY_INPUT, "")
    private val loading = MutableStateFlow(true)
    private val error = MutableStateFlow<ApiError?>(null)
    private val peerTyping = MutableStateFlow(false)
    private val hasMore = MutableStateFlow(false)
    private val pendingAttachments = MutableStateFlow<List<PendingAttachment>>(emptyList())

    /** Upload job per strip row — the remove-X cancels through this map. */
    private val uploadJobs = mutableMapOf<String, Job>()

    private var typingTtlJob: Job? = null
    private var lastTypingSentMs = 0L
    private var lastReadSeq = 0L
    private var loadingOlder = false

    val uiState: StateFlow<UiState> = combine(
        messageRepository.messagesFlow(conversationId),
        conversationRepository.conversationFlow(conversationId),
        input,
        peerTyping,
        error,
    ) { messages, conversation, inputValue, typing, err ->
        UiState(
            messages = messages,
            sending = messages.filter { it.status != DeliveryStatus.SENT },
            input = inputValue,
            peerTyping = typing,
            peerName = conversation?.displayName?.takeIf { it.isNotBlank() },
            isGroup = conversation?.isGroup == true,
            error = err,
        )
    }.combine(session.isOpen) { state, open -> state.copy(connected = open) }
        .combine(hasMore) { state, more -> state.copy(hasMore = more) }
        .combine(loading) { state, isLoading -> state.copy(loading = isLoading) }
        .combine(pendingAttachments) { state, pending ->
            state.copy(pendingAttachments = pending)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        viewModelScope.launch {
            messageRepository.openConversation(conversationId)
            when (val result = messageRepository.refreshHistory(conversationId)) {
                is Result.Success -> hasMore.value = result.value.hasMore
                is Result.Failure -> error.value = result.error
            }
            loading.value = false
        }
        viewModelScope.launch { observeIncomingReads() }
        viewModelScope.launch { observeTyping() }
    }

    /** Marks the newest incoming seq read while the screen is open. */
    private suspend fun observeIncomingReads() {
        messageRepository.messagesFlow(conversationId).collect { messages ->
            val newestIncoming = messages
                .filter { !it.mine }
                .mapNotNull { it.seq }
                .maxOrNull() ?: return@collect
            if (newestIncoming > lastReadSeq) {
                lastReadSeq = newestIncoming
                messageRepository.sendRead(conversationId, newestIncoming)
            }
        }
    }

    /** Peer typing → indicator on for [PEER_TYPING_TTL_MS], refreshed per event. */
    private suspend fun observeTyping() {
        messageRepository.typingEvents.collect { event ->
            if (event.conversationId != conversationId) return@collect
            if (messageRepository.isMine(event.userId)) return@collect
            peerTyping.value = true
            typingTtlJob?.cancel()
            typingTtlJob = viewModelScope.launch {
                delay(PEER_TYPING_TTL_MS)
                peerTyping.value = false
            }
        }
    }

    // ---- user actions ----

    fun onInputChange(value: String) {
        savedStateHandle[KEY_INPUT] = value
        val now = System.currentTimeMillis()
        if (value.isNotBlank() && now - lastTypingSentMs >= TYPING_SEND_INTERVAL_MS) {
            lastTypingSentMs = now
            session.sendTyping(conversationId)
        }
    }

    /** Optimistic send — the outbox row renders SENDING immediately. */
    fun send() {
        val text = input.value.trim()
        if (text.isEmpty()) return
        savedStateHandle[KEY_INPUT] = ""
        viewModelScope.launch {
            messageRepository.send(conversationId, text)
        }
    }

    /** Tap a FAILED bubble → requeue and dispatch. */
    fun retryFailed(clientMessageId: String) {
        viewModelScope.launch { messageRepository.retry(clientMessageId) }
    }

    // ---- attachments (phase-09) ----

    /**
     * Stages each picked [Uri] in the preview strip immediately, uploads with
     * live progress, and on success hands the returned attachment id to the
     * same outbox pipeline a text send uses (type derived from MIME). A failed
     * upload marks its row FAILED — the X removes it; no outbox row is made.
     */
    fun sendAttachments(uris: List<Uri>) {
        for (uri in uris) {
            val localId = UUID.randomUUID().toString()
            val job = viewModelScope.launch {
                val local = runCatching { attachmentRepository.describe(uri) }.getOrNull()
                    ?: return@launch
                updatePending {
                    it + PendingAttachment(
                        localId = localId,
                        uri = uri,
                        displayName = local.displayName,
                        mimeType = local.mimeType,
                        type = local.type,
                        progress = 0f,
                        state = PendingAttachment.PendingState.UPLOADING,
                    )
                }
                attachmentRepository.upload(uri, local.mimeType).collect { event ->
                    when (event) {
                        is AttachmentUploadEvent.Progress -> updatePending { rows ->
                            rows.map { row ->
                                if (row.localId == localId) {
                                    row.copy(progress = event.fraction)
                                } else {
                                    row
                                }
                            }
                        }
                        is AttachmentUploadEvent.Uploaded -> {
                            updatePending { rows -> rows.filterNot { it.localId == localId } }
                            uploadJobs.remove(localId)
                            // Optimistic send — the outbox bubble replaces the
                            // strip row; content carries the display name.
                            messageRepository.send(
                                conversationId = conversationId,
                                content = local.displayName
                                    ?: event.attachment.originalName
                                    ?: local.type,
                                type = local.type,
                                attachments = listOf(event.attachment),
                            )
                        }
                        is AttachmentUploadEvent.Failed -> updatePending { rows ->
                            rows.map { row ->
                                if (row.localId == localId) {
                                    row.copy(
                                        state = PendingAttachment.PendingState.FAILED,
                                    )
                                } else {
                                    row
                                }
                            }
                        }
                    }
                }
                uploadJobs.remove(localId)
            }
            uploadJobs[localId] = job
            // An instant-completing upload removes itself before the put —
            // don't leave a dead job registered for it.
            if (job.isCompleted) uploadJobs.remove(localId)
        }
    }

    /** Remove-X on a strip row: cancel its upload and drop the row. */
    fun removePendingAttachment(localId: String) {
        uploadJobs.remove(localId)?.cancel()
        updatePending { rows -> rows.filterNot { it.localId == localId } }
    }

    private fun updatePending(transform: (List<PendingAttachment>) -> List<PendingAttachment>) {
        pendingAttachments.update(transform)
    }

    /** FILE bubble tap: bytes → cache file for ACTION_VIEW. Null on failure. */
    suspend fun attachmentFile(attachment: AttachmentDto): File? {
        val id = attachment.id ?: return null
        return attachmentRepository.downloadToCache(id, attachment.originalName).getOrNull()
    }

    /** Loads the previous history page (cursor = oldest loaded seq). */
    fun loadOlder() {
        if (loadingOlder) return
        val oldest = uiState.value.messages.mapNotNull { it.seq }.minOrNull() ?: return
        loadingOlder = true
        viewModelScope.launch {
            when (val result = messageRepository.refreshHistory(conversationId, beforeSeq = oldest)) {
                is Result.Success -> hasMore.value = result.value.hasMore
                is Result.Failure -> error.value = result.error
            }
            loadingOlder = false
        }
    }

    override fun onCleared() = messageRepository.closeConversation(conversationId)

    companion object {
        const val TYPING_SEND_INTERVAL_MS = 3_000L
        const val PEER_TYPING_TTL_MS = 5_000L
        private const val KEY_INPUT = "chat_input"
    }
}
