package com.nexachat.feature.contacts

import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.network.dto.UserDto
import com.nexachat.data.Contact
import com.nexachat.data.ContactsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Coroutine bridge and suspend-only repository helpers for the Java ViewModel. */
internal fun newContactsEventChannel(): Channel<ContactsViewModel.Event> =
    Channel(Channel.BUFFERED)

internal fun contactsEventsFlow(
    channel: Channel<ContactsViewModel.Event>,
): Flow<ContactsViewModel.Event> = channel.receiveAsFlow()

internal fun contactsUiState(
    localState: StateFlow<ContactsViewModel.UiState>,
    contactsFlow: StateFlow<List<Contact>?>,
    scope: CoroutineScope,
): StateFlow<ContactsViewModel.UiState> =
    combine(localState, contactsFlow) { state, sharedContacts ->
        state.withContacts(sharedContacts ?: state.contacts)
    }.stateIn(scope, SharingStarted.Eagerly, localState.value)

internal suspend fun contactsLoadContacts(
    repository: ContactsRepository,
    uiState: MutableStateFlow<ContactsViewModel.UiState>,
) {
    uiState.update { it.withLoading(true).withError(null) }
    when (val result = repository.contacts()) {
        is Result.Success -> uiState.update {
            it.withLoading(false).withContactsLoaded(true)
        }
        is Result.Failure -> uiState.update {
            it.withLoading(false).withError(result.error).withContactsLoaded(true)
        }
    }
}

internal suspend fun contactsSearch(
    repository: ContactsRepository,
    uiState: MutableStateFlow<ContactsViewModel.UiState>,
    query: String,
    phoneQuery: Boolean,
) {
    uiState.update { it.withLoading(true).withError(null).withSearched(true) }
    val result = if (phoneQuery) {
        repository.searchByPhone(query)
    } else {
        repository.searchByName(query)
    }
    when (result) {
        is Result.Success -> uiState.update {
            it.withLoading(false).withSearchResults(result.value)
        }
        is Result.Failure -> uiState.update {
            it.withLoading(false)
                .withError(result.error)
                .withSearchResults(emptyList())
        }
    }
}

internal suspend fun contactsFindUserForAdd(
    repository: ContactsRepository,
    uiState: MutableStateFlow<ContactsViewModel.UiState>,
    phone: String,
) {
    uiState.update {
        it.withAddSearching(true).withAddError(null).withAddResult(null)
    }
    when (val result = repository.searchByPhone(phone)) {
        is Result.Success -> {
            val user = result.value.firstOrNull()
            uiState.update {
                it.withAddSearching(false)
                    .withAddResult(user)
                    .withAddError(if (user == null) ApiError.Unknown("USER_NOT_FOUND") else null)
            }
        }
        is Result.Failure -> uiState.update {
            it.withAddSearching(false).withAddError(result.error)
        }
    }
}

internal suspend fun contactsConfirmAdd(
    repository: ContactsRepository,
    uiState: MutableStateFlow<ContactsViewModel.UiState>,
    phone: String,
) {
    uiState.update { it.withAdding(true).withAddError(null) }
    if (contactsRejectOwnPhone(repository, phone)) {
        uiState.update {
            it.withAdding(false).withAddError(ApiError.Unknown(SelfContactGuard.ERROR_CODE))
        }
        return
    }
    when (val result = repository.addContact(phone)) {
        is Result.Success -> uiState.update {
            it.withAdding(false).withAddDone(true)
        }
        is Result.Failure -> uiState.update {
            it.withAdding(false).withAddError(result.error)
        }
    }
}

internal suspend fun contactsSaveAlias(
    repository: ContactsRepository,
    uiState: MutableStateFlow<ContactsViewModel.UiState>,
    contact: Contact,
    alias: String?,
) {
    uiState.update { it.withDetailBusy(true).withDetailError(null) }
    when (val result = repository.setAlias(contact.userId, alias)) {
        is Result.Success -> uiState.update {
            it.withDetailBusy(false).withDetailContact(result.value)
        }
        is Result.Failure -> uiState.update {
            it.withDetailBusy(false).withDetailError(result.error)
        }
    }
}

internal suspend fun contactsRemoveSelectedContact(
    repository: ContactsRepository,
    uiState: MutableStateFlow<ContactsViewModel.UiState>,
    contact: Contact,
) {
    uiState.update { it.withDetailBusy(true).withDetailError(null) }
    when (val result = repository.removeContact(contact.userId)) {
        is Result.Success -> uiState.update {
            it.withDetailBusy(false).withDetailUser(null).withDetailContact(null)
        }
        is Result.Failure -> uiState.update {
            it.withDetailBusy(false).withDetailError(result.error)
        }
    }
}

internal suspend fun contactsAddDetailContact(
    repository: ContactsRepository,
    uiState: MutableStateFlow<ContactsViewModel.UiState>,
    user: UserDto,
) {
    uiState.update { it.withDetailBusy(true).withDetailError(null) }
    if (contactsRejectOwnPhone(repository, user.phone)) {
        uiState.update {
            it.withDetailBusy(false)
                .withDetailError(ApiError.Unknown(SelfContactGuard.ERROR_CODE))
        }
        return
    }
    when (val result = repository.addContact(user.phone)) {
        is Result.Success -> uiState.update {
            it.withDetailBusy(false).withDetailContact(result.value)
        }
        is Result.Failure -> uiState.update {
            it.withDetailBusy(false).withDetailError(result.error)
        }
    }
}

internal suspend fun contactsMessageSelected(
    repository: ContactsRepository,
    uiState: MutableStateFlow<ContactsViewModel.UiState>,
    events: Channel<ContactsViewModel.Event>,
    user: UserDto,
) {
    uiState.update { it.withDetailBusy(true).withDetailError(null) }
    when (
        val result = repository.openDirectConversation(
            userId = user.id,
            phone = user.phone,
        )
    ) {
        is Result.Success -> {
            uiState.update { it.withDetailBusy(false) }
            events.send(ContactsViewModel.Event.OpenConversation(result.value))
        }
        is Result.Failure -> uiState.update {
            it.withDetailBusy(false).withDetailError(result.error)
        }
    }
}

private suspend fun contactsRejectOwnPhone(
    repository: ContactsRepository,
    phone: String,
): Boolean {
    val me = repository.getMe()
    return me is Result.Success &&
        SelfContactGuard.isOwnPhone(phone, me.value.phone)
}
