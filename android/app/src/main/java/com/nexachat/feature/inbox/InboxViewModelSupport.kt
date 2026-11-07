package com.nexachat.feature.inbox

import com.nexachat.core.common.Result
import com.nexachat.core.storage.TokenStore
import com.nexachat.data.ConversationRepository
import com.nexachat.data.TimeAndJwt
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update

/**
 * Suspend-only helpers for [InboxViewModel] (Java). Keeps token read, Flow
 * collection, and REST refresh off the UI thread without runBlocking.
 */
internal suspend fun inboxLoadCurrentUserId(
    tokenStore: TokenStore,
    moshi: Moshi,
    uiState: MutableStateFlow<InboxUiState>,
) {
    val accessToken = tokenStore.readTokens()?.accessToken
    val currentUserId = accessToken?.let { token -> TimeAndJwt.userIdFromJwt(token, moshi) }
    uiState.update { it.withCurrentUserId(currentUserId) }
}

internal suspend fun inboxCollectConversationRows(
    conversationRepository: ConversationRepository,
    uiState: MutableStateFlow<InboxUiState>,
) {
    conversationRepository.inboxFlow().collect { conversations ->
        uiState.update { it.withConversations(conversations, false) }
    }
}

internal suspend fun inboxRefreshInbox(
    conversationRepository: ConversationRepository,
    uiState: MutableStateFlow<InboxUiState>,
) {
    when (val result = conversationRepository.refreshInbox()) {
        is Result.Success -> uiState.update { it.withError(null) }
        is Result.Failure -> uiState.update { it.withError(result.error) }
    }
}
