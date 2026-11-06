package com.nexachat.feature.inbox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.storage.TokenStore
import com.nexachat.data.ConversationRepository
import com.nexachat.data.ConversationSummary
import com.nexachat.data.userIdFromJwt
import com.squareup.moshi.Moshi
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs InboxScreen (plan §12.1 feature/inbox). The list is a pure Room stream
 * via [ConversationRepository.inboxFlow] — WS pushes only trigger repository
 * refreshes, so the VM never handles frames. Unread badges come straight from
 * `unreadCount`; [UiState.totalUnread] feeds any app-level badge.
 */
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val conversationRepository: ConversationRepository,
    private val tokenStore: TokenStore? = null,
    private val moshi: Moshi? = null,
) : ViewModel() {

    data class UiState(
        val conversations: List<ConversationSummary> = emptyList(),
        val currentUserId: String? = null,
        val loading: Boolean = true,
        val error: ApiError? = null,
    ) {
        /** Sum of per-row unread — the app-level badge count. */
        val totalUnread: Int get() = conversations.sumOf { it.unreadCount }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val accessToken = tokenStore?.readTokens()?.accessToken
            val currentUserId = accessToken?.let { token ->
                moshi?.let { adapter -> userIdFromJwt(token, adapter) }
            }
            _uiState.update { it.copy(currentUserId = currentUserId) }
        }
        viewModelScope.launch {
            conversationRepository.inboxFlow().collect { conversations ->
                _uiState.update { it.copy(conversations = conversations, loading = false) }
            }
        }
        refresh()
    }

    /** Pulls the authoritative inbox from REST (launch + user refresh). */
    fun refresh() {
        viewModelScope.launch {
            when (val result = conversationRepository.refreshInbox()) {
                is Result.Success -> _uiState.update { it.copy(error = null) }
                is Result.Failure -> _uiState.update { it.copy(error = result.error) }
            }
        }
    }
}
