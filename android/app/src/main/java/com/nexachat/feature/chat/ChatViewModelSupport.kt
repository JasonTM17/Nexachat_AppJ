package com.nexachat.feature.chat

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.network.MessageApi
import com.nexachat.core.network.dto.AttachmentDto
import com.nexachat.core.network.dto.MessageDto
import com.nexachat.core.network.stomp.RealtimeSession
import com.nexachat.data.AttachmentRepository
import com.nexachat.data.AttachmentUploadEvent
import com.nexachat.data.ChatMessage
import com.nexachat.data.ConversationRepository
import com.nexachat.data.DeliveryStatus
import com.nexachat.data.MessageJump
import com.nexachat.data.MessageRepository
import com.nexachat.feature.chat.ChatViewModel.UiState
import com.nexachat.feature.media.PendingAttachment
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

private fun ChatViewModel.ChatSearchState.copy(
    query: String = this.query,
    loading: Boolean = this.loading,
    results: List<ChatMessage> = this.results,
    hasMore: Boolean = this.hasMore,
    error: ApiError? = this.error,
): ChatViewModel.ChatSearchState = ChatViewModel.ChatSearchState(query, loading, results, hasMore, error)

/** Owns coroutine jobs and repository/Flow work for the Java ViewModel. */
internal class ChatViewModelSupport(
    private val conversationId: String,
    private val savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
    private val attachmentRepository: AttachmentRepository,
    private val session: RealtimeSession,
    private val scope: CoroutineScope,
    private val mutableUiState: MutableStateFlow<UiState>,
    private val mutableSearchState: MutableStateFlow<ChatViewModel.ChatSearchState>,
    private val input: StateFlow<String>,
    private val loading: MutableStateFlow<Boolean>,
    private val error: MutableStateFlow<ApiError?>,
    private val peerTyping: MutableStateFlow<Boolean>,
    private val hasMore: MutableStateFlow<Boolean>,
    private val pendingAttachments: MutableStateFlow<List<PendingAttachment>>,
    private val replyTarget: MutableStateFlow<ChatViewModel.ReplyTarget?>,
) {
    private val actionEventsFlow = MutableSharedFlow<ChatViewModel.ChatActionEvent>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val actionEvents: SharedFlow<ChatViewModel.ChatActionEvent> = actionEventsFlow.asSharedFlow()

    private val jumpTargetsFlow = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val jumpTargets: SharedFlow<String> = jumpTargetsFlow.asSharedFlow()

    private val uploadJobs = mutableMapOf<String, Job>()
    private var searchJob: Job? = null
    private var searchCursor: Long? = null
    private var typingTtlJob: Job? = null
    private var lastTypingSentMs = 0L
    private var lastReadSeq = 0L
    private val loadingOlderMutex = Mutex()

    fun start() {
        scope.launch { collectUiState() }
        scope.launch {
            messageRepository.openConversation(conversationId)
            when (val result = messageRepository.refreshHistory(conversationId, null)) {
                is Result.Success -> hasMore.value = result.value.hasMore
                is Result.Failure -> error.value = result.error
            }
            loading.value = false
        }
        scope.launch { observeIncomingReads() }
        scope.launch { observeTyping() }
        scope.launch {
            messageRepository.interactionEvents.collect { error.value = it }
        }
    }

    private suspend fun collectUiState() {
        combine(
            messageRepository.messagesFlow(conversationId),
            conversationRepository.conversationFlow(conversationId),
            input,
            peerTyping,
            error,
        ) { messages, conversation, inputValue, typing, currentError ->
            UiState.fromBase(messages, conversation, inputValue, typing, currentError)
        }
            .combine(session.isOpen) { state, open -> state.withConnected(open) }
            .combine(hasMore) { state, more -> state.withHasMore(more) }
            .combine(loading) { state, isLoading -> state.withLoading(isLoading) }
            .combine(pendingAttachments) { state, pending -> state.withPendingAttachments(pending) }
            .combine(replyTarget) { state, reply -> state.withReplyTarget(reply) }
            .combine(messageRepository.pinsFlow(conversationId)) { state, pins -> state.withPins(pins) }
            .collect { mutableUiState.value = it }
    }

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

    private suspend fun observeTyping() {
        messageRepository.typingEvents.collect { event ->
            if (event.conversationId != conversationId) return@collect
            if (messageRepository.isMine(event.userId)) return@collect
            peerTyping.value = true
            typingTtlJob?.cancel()
            typingTtlJob = scope.launch {
                delay(ChatViewModel.PEER_TYPING_TTL_MS)
                peerTyping.value = false
            }
        }
    }

    fun onInputChange(value: String) {
        savedStateHandle[ChatViewModel.KEY_INPUT] = value
        val now = System.currentTimeMillis()
        if (value.isNotBlank() && now - lastTypingSentMs >= ChatViewModel.TYPING_SEND_INTERVAL_MS) {
            lastTypingSentMs = now
            session.sendTyping(conversationId)
        }
    }

    fun send() {
        val text = input.value.trim()
        if (text.isEmpty()) return
        val replyToId = replyTarget.value?.messageId
        savedStateHandle[ChatViewModel.KEY_INPUT] = ""
        replyTarget.value = null
        scope.launch {
            messageRepository.send(
                conversationId,
                text,
                replyToId,
                MessageDto.TYPE_TEXT,
                emptyList(),
            )
        }
    }

    fun retryFailed(clientMessageId: String) {
        scope.launch { messageRepository.retry(clientMessageId) }
    }

    fun jumpToMessage(messageId: String) {
        scope.launch {
            when (val jump = messageRepository.loadUntilLoaded(conversationId, messageId, 20)) {
                is MessageJump.Found -> jumpTargetsFlow.emit(messageId)
                is MessageJump.NotFound -> error.value = ApiError.Unknown(ChatViewModel.ERROR_MESSAGE_GONE)
                is MessageJump.Failed -> error.value = jump.error
            }
        }
    }

    fun editMessage(messageId: String, newContent: String) {
        val trimmed = newContent.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            when (val result = messageRepository.editMessage(conversationId, messageId, trimmed)) {
                is Result.Success -> actionEventsFlow.tryEmit(
                    ChatViewModel.ChatActionEvent.MessageEdited(messageId),
                )
                is Result.Failure -> error.value = result.error
            }
        }
    }

    fun deleteMessage(messageId: String) {
        scope.launch {
            when (val result = messageRepository.deleteMessage(conversationId, messageId)) {
                is Result.Success -> Unit
                is Result.Failure -> error.value = result.error
            }
        }
    }

    fun forwardMessage(messageId: String, targetConversationId: String) {
        val message = mutableUiState.value.messages.firstOrNull { it.id == messageId } ?: return
        if (message.status != DeliveryStatus.SENT || message.isDeleted) return
        val content = message.content?.trim().orEmpty()
        if (content.isEmpty()) return
        scope.launch {
            messageRepository.send(
                targetConversationId,
                content,
                null,
                MessageDto.TYPE_TEXT,
                emptyList(),
            )
            actionEventsFlow.tryEmit(
                ChatViewModel.ChatActionEvent.MessageForwarded(targetConversationId),
            )
        }
    }

    fun search(query: String) {
        searchJob?.cancel()
        searchCursor = null
        mutableSearchState.value = ChatViewModel.ChatSearchState(query, false, emptyList(), false, null)
        val trimmed = query.trim()
        if (trimmed.length < ChatViewModel.MIN_SEARCH_CHARS) return
        searchJob = scope.launch {
            delay(ChatViewModel.SEARCH_DEBOUNCE_MS)
            performSearch(trimmed, beforeSeq = null, append = false)
        }
    }

    fun loadMoreSearch() {
        val state = mutableSearchState.value
        if (state.loading || !state.hasMore) return
        val cursor = searchCursor ?: return
        val trimmed = state.query.trim()
        if (trimmed.length < ChatViewModel.MIN_SEARCH_CHARS) return
        searchJob?.cancel()
        searchJob = scope.launch {
            performSearch(trimmed, beforeSeq = cursor, append = true)
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        searchJob = null
        searchCursor = null
        mutableSearchState.value = ChatViewModel.ChatSearchState()
    }

    private suspend fun performSearch(query: String, beforeSeq: Long?, append: Boolean) {
        mutableSearchState.update { it.copy(loading = true, error = null) }
        when (val result = messageRepository.searchMessages(
            conversationId,
            query,
            beforeSeq,
            MessageApi.DEFAULT_SEARCH_LIMIT,
        )) {
            is Result.Success -> {
                if (mutableSearchState.value.query.trim() != query) return
                val page = result.value
                mutableSearchState.update { current ->
                    val merged = if (append) {
                        (current.results + page.items).distinctBy { it.id }
                    } else {
                        page.items
                    }
                    current.copy(loading = false, results = merged, hasMore = page.hasMore)
                }
                searchCursor = page.nextBeforeSeq
                    ?: page.items.minByOrNull { it.seq ?: Long.MAX_VALUE }?.seq
            }
            is Result.Failure -> {
                if (mutableSearchState.value.query.trim() != query) return
                mutableSearchState.update { it.copy(loading = false, error = result.error) }
            }
        }
    }

    fun toggleReaction(message: ChatMessage, emoji: String) {
        if (message.status != DeliveryStatus.SENT) return
        scope.launch {
            messageRepository.toggleReaction(message.id, conversationId, emoji)
        }
    }

    fun togglePin(message: ChatMessage) {
        if (message.status != DeliveryStatus.SENT) return
        scope.launch {
            if (mutableUiState.value.isPinned(message.id)) {
                messageRepository.unpinMessage(conversationId, message.id)
            } else {
                messageRepository.pinMessage(conversationId, message.id)
            }
        }
    }

    fun sendAttachments(uris: List<Uri>) {
        for (uri in uris) {
            val localId = UUID.randomUUID().toString()
            val job = scope.launch {
                val local = runCatching { attachmentRepository.describe(uri) }.getOrNull()
                    ?: return@launch
                updatePending {
                    it + PendingAttachment(
                        localId,
                        uri,
                        local.displayName,
                        local.mimeType,
                        local.type,
                        0f,
                        PendingAttachment.PendingState.UPLOADING,
                    )
                }
                attachmentRepository.upload(uri, local.mimeType).collect { event ->
                    when (event) {
                        is AttachmentUploadEvent.Progress -> updatePending { rows ->
                            rows.map { row ->
                                if (row.localId == localId) row.withProgress(event.fraction) else row
                            }
                        }
                        is AttachmentUploadEvent.Uploaded -> {
                            updatePending { rows -> rows.filterNot { it.localId == localId } }
                            uploadJobs.remove(localId)
                            messageRepository.send(
                                conversationId,
                                local.displayName
                                    ?: event.attachment.originalName
                                    ?: local.type,
                                null,
                                AttachmentDto.messageTypeForMime(
                                    event.attachment.mimeType?.takeIf(String::isNotBlank)
                                        ?: local.mimeType,
                                ),
                                listOf(event.attachment),
                            )
                        }
                        is AttachmentUploadEvent.Failed -> updatePending { rows ->
                            rows.map { row ->
                                if (row.localId == localId) {
                                    row.withState(PendingAttachment.PendingState.FAILED)
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
            if (job.isCompleted) uploadJobs.remove(localId)
        }
    }

    fun removePendingAttachment(localId: String) {
        uploadJobs.remove(localId)?.cancel()
        updatePending { rows -> rows.filterNot { it.localId == localId } }
    }

    private fun updatePending(transform: (List<PendingAttachment>) -> List<PendingAttachment>) {
        pendingAttachments.update(transform)
    }

    suspend fun attachmentFile(attachment: AttachmentDto): File? {
        val id = attachment.id ?: return null
        return attachmentRepository.downloadToCache(id, attachment.originalName).getOrNull()
    }

    fun loadOlder() {
        if (!loadingOlderMutex.tryLock()) return
        val oldest = mutableUiState.value.messages.mapNotNull { it.seq }.minOrNull()
        if (oldest == null) {
            loadingOlderMutex.unlock()
            return
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                when (val result = messageRepository.refreshHistory(conversationId, oldest)) {
                    is Result.Success -> hasMore.value = result.value.hasMore
                    is Result.Failure -> error.value = result.error
                }
            } finally {
                loadingOlderMutex.unlock()
            }
        }
    }
}

/** Preserves the screen's suspend call while keeping suspend code out of Java. */
internal suspend fun ChatViewModel.attachmentFile(attachment: AttachmentDto): File? =
    supportSession().attachmentFile(attachment)
