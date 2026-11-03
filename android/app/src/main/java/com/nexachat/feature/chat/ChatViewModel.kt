package com.nexachat.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.network.stomp.RealtimeSession
import com.nexachat.data.ChatMessage
import com.nexachat.data.ConversationRepository
import com.nexachat.data.DeliveryStatus
import com.nexachat.data.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs `chat/{conversationId}` (plan §12.2 UDF).
 *
 * All render state derives from flows: messages come from Room+outbox via
 * [MessageRepository.messagesFlow] (restart-safe — nothing lives only in
 * memory), the draft/input and per-screen flags are MutableStateFlows merged
 * by [uiState]. A rotation is pure recomposition: the VM survives and Room
 * re-emits.
 *
 * Typing (plan §11.3.6): outbound signals are throttled to one SEND per
 * [TYPING_SEND_INTERVAL_MS] while text is non-blank; an inbound peer signal
 * shows the indicator for [PEER_TYPING_TTL_MS] unless refreshed.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val messageRepository: MessageRepository,
    private val conversationRepository: ConversationRepository,
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
    )

    private val input = MutableStateFlow("")
    private val loading = MutableStateFlow(true)
    private val error = MutableStateFlow<ApiError?>(null)
    private val peerTyping = MutableStateFlow(false)
    private val hasMore = MutableStateFlow(false)

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
        input.value = value
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
        input.value = ""
        viewModelScope.launch {
            messageRepository.send(conversationId, text)
        }
    }

    /** Tap a FAILED bubble → requeue and dispatch. */
    fun retryFailed(clientMessageId: String) {
        viewModelScope.launch { messageRepository.retry(clientMessageId) }
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
    }
}
