package com.nexachat.feature.group

import com.nexachat.core.common.Result
import com.nexachat.data.ContactsRepository
import com.nexachat.data.ConversationRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

/** Suspend helpers for the Java [CreateGroupViewModel]. */
internal fun newCreateGroupEventChannel(): Channel<CreateGroupEvent> =
    Channel(Channel.BUFFERED)

internal fun createGroupEventsFlow(channel: Channel<CreateGroupEvent>): Flow<CreateGroupEvent> =
    channel.receiveAsFlow()

internal suspend fun createGroupLoadContacts(
    contactsRepository: ContactsRepository,
    uiState: MutableStateFlow<CreateGroupUiState>,
) {
    uiState.update { it.withLoading(true, null) }
    when (val result = contactsRepository.contacts()) {
        is Result.Success -> uiState.update { it.withContacts(result.value, true) }
        is Result.Failure -> uiState.update { it.withContactsFailure(result.error) }
    }
}

internal suspend fun createGroupCreate(
    conversationRepository: ConversationRepository,
    uiState: MutableStateFlow<CreateGroupUiState>,
    events: Channel<CreateGroupEvent>,
) {
    val state = uiState.value
    if (!state.canCreate) return
    uiState.update { it.withCreating(true, null) }
    when (
        val result = conversationRepository.createGroup(state.title, state.selectedIds.toList())
    ) {
        is Result.Success -> {
            uiState.update { it.withCreating(false, null) }
            events.send(CreateGroupEvent.Created(result.value))
        }
        is Result.Failure -> uiState.update { it.withCreating(false, result.error) }
    }
}
