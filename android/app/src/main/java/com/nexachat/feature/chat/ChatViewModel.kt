package com.nexachat.feature.chat

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.network.dto.AttachmentDto
import com.nexachat.core.network.dto.MessageDto
import com.nexachat.core.network.dto.messageTypeForMime
import com.nexachat.core.network.MessageApi
import com.nexachat.core.network.stomp.RealtimeSession
import com.nexachat.data.AttachmentRepository
import com.nexachat.data.AttachmentUploadEvent
import com.nexachat.data.ChatMessage
import com.nexachat.data.ConversationRepository
import com.nexachat.data.DeliveryStatus
import com.nexachat.data.MessageJump
import com.nexachat.data.MessageRepository
import com.nexachat.data.PeerPresence
import com.nexachat.feature.media.PendingAttachment
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * In-conversation search state (F4). [results] are newest-first and already
 * hydrated ([ChatMessage] carries reactions/attachments); picking one is the
 * screen's job via the existing [ChatViewModel.jumpToMessage].
 */
data class ChatSearchState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<ChatMessage> = emptyList(),
    val hasMore: Boolean = false,
    val error: ApiError? = null,
)

/** One-shot chat action results the UI may surface (e.g. a forward toast). */
sealed interface ChatActionEvent {
    data class MessageForwarded(val targetConversationId: String) : ChatActionEvent
}

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

    /** Reply draft — the message a swipe/Reply action is answering (phase-10). */
    data class ReplyTarget(
        /** Server message id — pending rows can never be reply targets. */
        val messageId: String,
        val senderName: String?,
        /** Content snippet rendered in the preview bar. */
        val snippet: String?,
    )

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
        /** GROUP pin is ADMIN-only; DIRECT members may always pin (server enforces too). */
        val canPin: Boolean = true,
        val connected: Boolean = true,
        val hasMore: Boolean = false,
        val error: ApiError? = null,
        /** In-flight uploads staged above the input bar (phase-09 preview strip). */
        val pendingAttachments: List<PendingAttachment> = emptyList(),
        /** Non-null while the composer is answering a message (phase-10). */
        val replyTarget: ReplyTarget? = null,
        /** Pinned messages, newest pin first — drives the banner. */
        val pins: List<ChatMessage> = emptyList(),
        /** Other members' max read watermark — DIRECT bubbles show "Đã xem" at/below it. */
        val peerReadSeq: Long = 0,
        /** DIRECT peer presence (F3); null for groups or a peer who hides it. */
        val peerPresence: PeerPresence? = null,
        /** DIRECT peer user id from the inbox row — available before any message arrives. */
        val otherUserId: String? = null,
    ) {
        /** O(1) "is this message pinned" for the long-press menu. */
        fun isPinned(messageId: String): Boolean = pins.any { it.id == messageId }
    }

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
    private val replyTarget = MutableStateFlow<ReplyTarget?>(null)

    private val _searchState = MutableStateFlow(ChatSearchState())

    /** Search results/debounce state consumed by the search sheet. */
    val searchState: StateFlow<ChatSearchState> = _searchState.asStateFlow()

    private val _actionEvents = MutableSharedFlow<ChatActionEvent>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** One-shot results (currently forward) for snackbar-style feedback. */
    val actionEvents: SharedFlow<ChatActionEvent> = _actionEvents.asSharedFlow()

    private var searchJob: Job? = null

    /** Cursor for the next search page — the server's `nextBeforeSeq`. */
    private var searchCursor: Long? = null

    /** Upload job per strip row — the remove-X cancels through this map. */
    private val uploadJobs = mutableMapOf<String, Job>()

    private var typingTtlJob: Job? = null
    private var lastTypingSentMs = 0L
    private var lastReadSeq = 0L
    private var loadingOlder = false

    private val _jumpTargets = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Ids the message list should scroll to. Emitted only once the target is
     * in the loaded window, so the UI's index lookup cannot miss it.
     */
    val jumpTargets: SharedFlow<String> = _jumpTargets.asSharedFlow()

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
            canPin = conversation?.isGroup != true ||
                conversation.myRole == "ADMIN",
            peerReadSeq = conversation?.peerReadSeq ?: 0L,
            peerPresence = conversation?.peerPresence,
            otherUserId = conversation?.otherUserId?.takeIf { it.isNotBlank() },
            error = err,
        )
    }.combine(session.isOpen) { state, open -> state.copy(connected = open) }
        .combine(hasMore) { state, more -> state.copy(hasMore = more) }
        .combine(loading) { state, isLoading -> state.copy(loading = isLoading) }
        .combine(pendingAttachments) { state, pending ->
            state.copy(pendingAttachments = pending)
        }
        .combine(replyTarget) { state, reply -> state.copy(replyTarget = reply) }
        .combine(messageRepository.pinsFlow(conversationId)) { state, pins ->
            state.copy(pins = pins)
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
        // Reaction/pin failures surface in the same inline error slot as
        // history failures — one render path for all chat errors.
        viewModelScope.launch {
            messageRepository.interactionEvents.collect { error.value = it }
        }
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
        val replyToId = replyTarget.value?.messageId
        savedStateHandle[KEY_INPUT] = ""
        replyTarget.value = null
        viewModelScope.launch {
            messageRepository.send(conversationId, text, replyToId = replyToId)
        }
    }

    /** Tap a FAILED bubble → requeue and dispatch. */
    fun retryFailed(clientMessageId: String) {
        viewModelScope.launch { messageRepository.retry(clientMessageId) }
    }

    /**
     * Quote / pin banner tap. A target outside the loaded window is paged in
     * (bounded) before [jumpTargets] fires, so the reply a user came from is
     * reachable even after restart or a long scroll.
     */
    fun jumpToMessage(messageId: String) {
        viewModelScope.launch {
            when (val jump = messageRepository.loadUntilLoaded(conversationId, messageId)) {
                MessageJump.Found -> _jumpTargets.emit(messageId)
                MessageJump.NotFound -> error.value = ApiError.Unknown(ERROR_MESSAGE_GONE)
                is MessageJump.Failed -> error.value = jump.error
            }
        }
    }

    // ---- edit / delete / forward (F1/F2/F5) ----

    /**
     * A message may be edited only while it is mine, delivered, live and
     * TEXT — the same rule the server enforces (`NOT_MESSAGE_AUTHOR` /
     * `VALIDATION_FAILED`). Pure so the UI can gate the affordance cheaply.
     */
    fun canEdit(message: ChatMessage): Boolean =
        message.mine &&
            message.status == DeliveryStatus.SENT &&
            !message.isDeleted &&
            message.type == MessageDto.TYPE_TEXT

    /** Delete is author-only but type-agnostic (attachments included). */
    fun canDelete(message: ChatMessage): Boolean =
        message.mine && message.status == DeliveryStatus.SENT && !message.isDeleted

    /**
     * Optimistic edit. Blank input never reaches the server; failures restore
     * the previous row in the repository and surface through [UiState.error].
     */
    fun editMessage(messageId: String, newContent: String) {
        val trimmed = newContent.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            when (val result = messageRepository.editMessage(conversationId, messageId, trimmed)) {
                is Result.Success -> Unit
                is Result.Failure -> error.value = result.error
            }
        }
    }

    /** Optimistic delete-for-everyone, with compare-and-restore on failure. */
    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            when (val result = messageRepository.deleteMessage(conversationId, messageId)) {
                is Result.Success -> Unit
                is Result.Failure -> error.value = result.error
            }
        }
    }

    /**
     * Client-only forward (F5): re-sends the same content as a NEW TEXT
     * message through the normal outbox path, so idempotency/retry are intact.
     * Attachments are not forwarded in this release. Emits [ChatActionEvent]
     * once the message is queued so the UI can confirm the target.
     */
    fun forwardMessage(messageId: String, targetConversationId: String) {
        val message = uiState.value.messages.firstOrNull { it.id == messageId } ?: return
        if (message.status != DeliveryStatus.SENT || message.isDeleted) return
        val content = message.content?.trim().orEmpty()
        if (content.isEmpty()) return
        viewModelScope.launch {
            messageRepository.send(
                conversationId = targetConversationId,
                content = content,
                type = MessageDto.TYPE_TEXT,
            )
            _actionEvents.tryEmit(ChatActionEvent.MessageForwarded(targetConversationId))
        }
    }

    // ---- search (F4) ----

    /**
     * Debounced in-conversation search. Each keystroke cancels the previous
     * job; sub-[MIN_SEARCH_CHARS] input clears the result set without a call,
     * mirroring the server's 2-char floor.
     */
    fun search(query: String) {
        searchJob?.cancel()
        searchCursor = null
        _searchState.value = ChatSearchState(query = query)
        val trimmed = query.trim()
        if (trimmed.length < MIN_SEARCH_CHARS) return
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            performSearch(trimmed, beforeSeq = null, append = false)
        }
    }

    /** Next page of the current search — no-op while loading or exhausted. */
    fun loadMoreSearch() {
        val state = _searchState.value
        if (state.loading || !state.hasMore) return
        val cursor = searchCursor ?: return
        val trimmed = state.query.trim()
        if (trimmed.length < MIN_SEARCH_CHARS) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            performSearch(trimmed, beforeSeq = cursor, append = true)
        }
    }

    /** Leaves search mode and drops any in-flight query. */
    fun clearSearch() {
        searchJob?.cancel()
        searchJob = null
        searchCursor = null
        _searchState.value = ChatSearchState()
    }

    private suspend fun performSearch(query: String, beforeSeq: Long?, append: Boolean) {
        _searchState.update { it.copy(loading = true, error = null) }
        when (val result = messageRepository.searchMessages(conversationId, query, beforeSeq)) {
            is Result.Success -> {
                // A newer keystroke won the race — drop this page.
                if (_searchState.value.query.trim() != query) return
                val page = result.value
                _searchState.update { current ->
                    val merged = if (append) {
                        (current.results + page.items).distinctBy { it.id }
                    } else {
                        page.items
                    }
                    current.copy(
                        loading = false,
                        results = merged,
                        hasMore = page.hasMore,
                    )
                }
                searchCursor = page.nextBeforeSeq
                    ?: page.items.minByOrNull { it.seq ?: Long.MAX_VALUE }?.seq
            }
            is Result.Failure -> {
                if (_searchState.value.query.trim() != query) return
                _searchState.update { it.copy(loading = false, error = result.error) }
            }
        }
    }

    // ---- interactions (phase-10) ----

    /**
     * Swipe-right or the Reply menu item → preview bar above the composer.
     * Only SENT messages qualify — a pending row's clientMessageId is
     * meaningless to the server's `replyToId` field.
     */
    fun setReplyTarget(message: ChatMessage) {
        if (message.status != DeliveryStatus.SENT) return
        replyTarget.value = ReplyTarget(
            messageId = message.id,
            senderName = message.senderDisplayName,
            snippet = message.content ?: message.type,
        )
    }

    fun clearReplyTarget() {
        replyTarget.value = null
    }

    /** Emoji tap in the bar or on a chip — repository optimises + reconciles. */
    fun toggleReaction(message: ChatMessage, emoji: String) {
        if (message.status != DeliveryStatus.SENT) return
        viewModelScope.launch {
            messageRepository.toggleReaction(message.id, conversationId, emoji)
        }
    }

    /**
     * Pin/Unpin menu item, reached only when [UiState.canPin] is true —
     * DIRECT allows any member, GROUP only when my inbox row carries the
     * ADMIN role (`ConversationEntity.myRole`). The server stays the real
     * boundary: a role that changed after the last inbox refresh still
     * answers 403, surfaced on [interactionEvents] as a snackbar.
     */
    fun togglePin(message: ChatMessage) {
        if (message.status != DeliveryStatus.SENT) return
        viewModelScope.launch {
            if (uiState.value.isPinned(message.id)) {
                messageRepository.unpinMessage(conversationId, message.id)
            } else {
                messageRepository.pinMessage(conversationId, message.id)
            }
        }
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
                                type = messageTypeForMime(
                                    event.attachment.mimeType?.takeIf(String::isNotBlank)
                                        ?: local.mimeType,
                                ),
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

        /** Search mirrors the server: 2-char floor, 300 ms debounce, 20/page. */
        const val MIN_SEARCH_CHARS = 2
        const val SEARCH_DEBOUNCE_MS = 300L
        const val SEARCH_PAGE_SIZE = MessageApi.DEFAULT_SEARCH_LIMIT

        /** The quoted/pinned message no longer exists within the page budget. */
        const val ERROR_MESSAGE_GONE = "MESSAGE_NOT_FOUND"
        private const val KEY_INPUT = "chat_input"
    }
}
