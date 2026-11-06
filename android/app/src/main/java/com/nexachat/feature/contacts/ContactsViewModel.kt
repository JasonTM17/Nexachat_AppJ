package com.nexachat.feature.contacts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.network.dto.UserDto
import com.nexachat.data.Contact
import com.nexachat.data.ContactsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs ContactsScreen (list + add-by-phone + detail sheets) and
 * UserSearchScreen — each nav destination gets its own instance via
 * `hiltViewModel()`.
 *
 * Field inputs and sheet selection live in [UiState] so they survive config
 * changes; one-shot navigation (open conversation) uses a [Channel]
 * (plan §12.2 — never SingleLiveEvent).
 */
@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val contactsRepository: ContactsRepository,
) : ViewModel() {

    data class UiState(
        // User search (UserSearchScreen).
        val query: String = "",
        val searchResults: List<UserDto> = emptyList(),
        /** True once a search has run — lets the UI show "no results" vs initial. */
        val searched: Boolean = false,
        // Contact list (ContactsScreen).
        val contacts: List<Contact> = emptyList(),
        val contactsLoaded: Boolean = false,
        val loading: Boolean = false,
        val error: ApiError? = null,
        // Add-by-phone sheet.
        val addPhone: String = "",
        val addSearching: Boolean = false,
        val addResult: UserDto? = null,
        val adding: Boolean = false,
        val addDone: Boolean = false,
        val addError: ApiError? = null,
        // Detail sheet — [detailContact] is null when [detailUser] is not yet a contact.
        val detailUser: UserDto? = null,
        val detailContact: Contact? = null,
        val aliasInput: String = "",
        val detailBusy: Boolean = false,
        val detailError: ApiError? = null,
    )

    sealed interface Event {
        data class OpenConversation(val conversationId: String) : Event
    }

    // The search query is SavedStateHandle-backed (restored below) so a typed
    // draft survives process death, not only rotation.
    private val _uiState = MutableStateFlow(
        UiState(query = savedStateHandle.get<String>(KEY_SEARCH_QUERY).orEmpty()),
    )

    /**
     * Contacts come from the repository's shared cache, not per-VM snapshots:
     * every nav destination owns a separate instance of this VM, so a list
     * mutated by the search screen's add/alias/remove must surface here too.
     */
    val uiState: StateFlow<UiState> =
        combine(_uiState, contactsRepository.contactsFlow) { state, shared ->
            state.copy(contacts = shared ?: state.contacts)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // The ViewModel survives rotation — loading here (not in the composable)
    // means no duplicate API call on orientation change (plan FR-G1).
    init {
        loadContacts()
    }

    // ---- contact list ----

    fun loadContacts() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            when (val result = contactsRepository.contacts()) {
                // The shared cache emits the list — only flags flip here.
                is Result.Success -> _uiState.update {
                    it.copy(loading = false, contactsLoaded = true)
                }
                is Result.Failure -> _uiState.update {
                    it.copy(loading = false, error = result.error, contactsLoaded = true)
                }
            }
        }
    }

    // ---- user search ----

    fun onQueryChange(value: String) {
        savedStateHandle[KEY_SEARCH_QUERY] = value
        _uiState.update {
            it.copy(query = value, error = null, searched = it.searched && value.isNotBlank())
        }
    }

    /** True when [query] looks like a phone input (starts with '+' or a digit). */
    private fun isPhoneQuery(query: String): Boolean =
        query.firstOrNull()?.let { it == '+' || it.isDigit() } == true

    /**
     * Runs the search: phone-input → exact `?phone=` lookup, anything else →
     * `?q=` name-prefix search. Explicit submit only — the endpoint is
     * rate-limited (30/min per user), so no per-keystroke calls.
     */
    fun search() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty() || _uiState.value.loading) return
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null, searched = true) }
            val result = if (isPhoneQuery(query)) {
                contactsRepository.searchByPhone(query)
            } else {
                contactsRepository.searchByName(query)
            }
            when (result) {
                is Result.Success -> _uiState.update {
                    it.copy(loading = false, searchResults = result.value)
                }
                is Result.Failure -> _uiState.update {
                    it.copy(loading = false, error = result.error, searchResults = emptyList())
                }
            }
        }
    }

    // ---- add-by-phone sheet ----

    fun onAddPhoneChange(value: String) =
        _uiState.update {
            it.copy(addPhone = value, addError = null, addResult = null, addDone = false)
        }

    /** Finds the user behind [UiState.addPhone] so the user can confirm before adding. */
    fun findUserForAdd() {
        val phone = _uiState.value.addPhone.trim()
        if (phone.isEmpty() || _uiState.value.addSearching) return
        viewModelScope.launch {
            _uiState.update { it.copy(addSearching = true, addError = null, addResult = null) }
            when (val result = contactsRepository.searchByPhone(phone)) {
                is Result.Success -> {
                    val user = result.value.firstOrNull()
                    _uiState.update {
                        it.copy(
                            addSearching = false,
                            addResult = user,
                            addError = if (user == null) ApiError.Unknown("USER_NOT_FOUND") else null,
                        )
                    }
                }
                is Result.Failure -> _uiState.update {
                    it.copy(addSearching = false, addError = result.error)
                }
            }
        }
    }

    /** Adds the user found by [findUserForAdd] (or re-adds by raw phone). */
    fun confirmAdd() {
        val state = _uiState.value
        val phone = state.addResult?.phone ?: state.addPhone.trim()
        if (phone.isEmpty() || state.adding) return
        viewModelScope.launch {
            _uiState.update { it.copy(adding = true, addError = null) }
            if (rejectOwnPhone(phone) { error ->
                    _uiState.update { it.copy(adding = false, addError = error) }
                }
            ) {
                return@launch
            }
            when (val result = contactsRepository.addContact(phone)) {
                is Result.Success -> _uiState.update {
                    it.copy(adding = false, addDone = true)
                }
                is Result.Failure -> _uiState.update {
                    it.copy(adding = false, addError = result.error)
                }
            }
        }
    }

    /** Resets the add-by-phone flow — call when the sheet is dismissed. */
    fun resetAddFlow() =
        _uiState.update {
            it.copy(
                addPhone = "",
                addSearching = false,
                addResult = null,
                adding = false,
                addDone = false,
                addError = null,
            )
        }

    /**
     * Blocks adding the signed-in phone before the request. A failed profile
     * lookup falls through so the server remains the authority.
     */
    private suspend fun rejectOwnPhone(phone: String, onReject: (ApiError) -> Unit): Boolean {
        val me = contactsRepository.getMe()
        if (me is Result.Success && SelfContactGuard.isOwnPhone(phone, me.value.phone)) {
            onReject(ApiError.Unknown(SelfContactGuard.ERROR_CODE))
            return true
        }
        return false
    }

    // ---- detail sheet ----

    fun openUserDetail(user: UserDto) {
        // The combined uiState holds the shared cache. The backing flow does not,
        // so a lookup here must read the repository cache directly.
        val known = contactsRepository.contactsFlow.value
            ?.firstOrNull { it.userId == user.id }
        _uiState.update {
            it.copy(
                detailUser = user,
                detailContact = known,
                aliasInput = known?.aliasName.orEmpty(),
                detailError = null,
            )
        }
    }

    fun openContactDetail(contact: Contact) =
        _uiState.update {
            it.copy(
                detailUser = UserDto(
                    contact.userId,
                    contact.phone,
                    contact.displayName,
                    null,
                ),
                detailContact = contact,
                aliasInput = contact.aliasName.orEmpty(),
                detailError = null,
            )
        }

    fun closeDetail() =
        _uiState.update {
            it.copy(
                detailUser = null,
                detailContact = null,
                aliasInput = "",
                detailBusy = false,
                detailError = null,
            )
        }

    fun onAliasChange(value: String) =
        _uiState.update { it.copy(aliasInput = value, detailError = null) }

    /** Persists [UiState.aliasInput] on the selected contact (blank clears the alias). */
    fun saveAlias() {
        val contact = _uiState.value.detailContact ?: return
        if (_uiState.value.detailBusy) return
        val alias = _uiState.value.aliasInput.trim().ifBlank { null }
        viewModelScope.launch {
            _uiState.update { it.copy(detailBusy = true, detailError = null) }
            when (val result = contactsRepository.setAlias(contact.userId, alias)) {
                is Result.Success -> _uiState.update { state ->
                    state.copy(detailBusy = false, detailContact = result.value)
                }
                is Result.Failure -> _uiState.update {
                    it.copy(detailBusy = false, detailError = result.error)
                }
            }
        }
    }

    fun removeSelectedContact() {
        val contact = _uiState.value.detailContact ?: return
        if (_uiState.value.detailBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(detailBusy = true, detailError = null) }
            when (val result = contactsRepository.removeContact(contact.userId)) {
                is Result.Success -> _uiState.update { state ->
                    state.copy(
                        detailBusy = false,
                        detailUser = null,
                        detailContact = null,
                    )
                }
                is Result.Failure -> _uiState.update {
                    it.copy(detailBusy = false, detailError = result.error)
                }
            }
        }
    }

    /** Adds [UiState.detailUser] as a contact from the detail sheet (search flow). */
    fun addDetailContact() {
        val user = _uiState.value.detailUser ?: return
        if (_uiState.value.detailBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(detailBusy = true, detailError = null) }
            if (rejectOwnPhone(user.phone) { error ->
                    _uiState.update { it.copy(detailBusy = false, detailError = error) }
                }
            ) {
                return@launch
            }
            when (val result = contactsRepository.addContact(user.phone)) {
                is Result.Success -> _uiState.update { state ->
                    state.copy(detailBusy = false, detailContact = result.value)
                }
                is Result.Failure -> _uiState.update {
                    it.copy(detailBusy = false, detailError = result.error)
                }
            }
        }
    }

    /**
     * Opens the direct conversation with the selected user and emits
     * [Event.OpenConversation] with the id — the nav host routes to `chat/{id}`.
     */
    fun messageSelected() {
        val user = _uiState.value.detailUser ?: return
        if (_uiState.value.detailBusy) return
        viewModelScope.launch {
            _uiState.update { it.copy(detailBusy = true, detailError = null) }
            when (
                val result = contactsRepository.openDirectConversation(
                    userId = user.id,
                    phone = user.phone,
                )
            ) {
                is Result.Success -> {
                    _uiState.update { it.copy(detailBusy = false) }
                    _events.send(Event.OpenConversation(result.value))
                }
                is Result.Failure -> _uiState.update {
                    it.copy(detailBusy = false, detailError = result.error)
                }
            }
        }
    }

    private companion object {
        const val KEY_SEARCH_QUERY = "search_query"
    }
}
