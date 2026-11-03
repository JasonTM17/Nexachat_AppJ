package com.nexachat.feature.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.data.Contact
import com.nexachat.data.ContactsRepository
import com.nexachat.data.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backs `group/create` (phase-08 FR-D1). Field inputs and the member
 * selection live in [UiState] so they survive config changes; the one-shot
 * "open the new conversation" navigation goes through [events] (plan §12.2 —
 * never SingleLiveEvent).
 */
@HiltViewModel
class CreateGroupViewModel @Inject constructor(
    private val contactsRepository: ContactsRepository,
    private val conversationRepository: ConversationRepository,
) : ViewModel() {

    data class UiState(
        val title: String = "",
        val contacts: List<Contact> = emptyList(),
        val selectedIds: Set<String> = emptySet(),
        val contactsLoaded: Boolean = false,
        val loading: Boolean = false,
        val creating: Boolean = false,
        val error: ApiError? = null,
    ) {
        /** Create stays disabled until the name and at least one member exist. */
        val canCreate: Boolean
            get() = title.isNotBlank() && selectedIds.isNotEmpty() && !creating
    }

    sealed interface Event {
        /** Carries the new conversation id — the nav host routes to `chat/{id}`. */
        data class Created(val conversationId: String) : Event
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // Loaded in init (not the composable) so rotation never refetches (FR-G1).
    init {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true, error = null) }
            when (val result = contactsRepository.contacts()) {
                is Result.Success -> _uiState.update {
                    it.copy(
                        loading = false,
                        contacts = result.value,
                        contactsLoaded = true,
                    )
                }
                is Result.Failure -> _uiState.update {
                    it.copy(loading = false, error = result.error, contactsLoaded = true)
                }
            }
        }
    }

    fun onTitleChange(value: String) =
        _uiState.update { it.copy(title = value, error = null) }

    fun toggleMember(userId: String) =
        _uiState.update { state ->
            state.copy(
                selectedIds = if (userId in state.selectedIds) {
                    state.selectedIds - userId
                } else {
                    state.selectedIds + userId
                },
            )
        }

    /** Called by the UI once a snackbar has been shown for [UiState.error]. */
    fun dismissError() = _uiState.update { it.copy(error = null) }

    /**
     * POSTs the group and emits [Event.Created] with the new conversation id;
     * a typed [ApiError] lands in state for the snackbar instead.
     */
    fun create() {
        val state = _uiState.value
        if (!state.canCreate) return
        viewModelScope.launch {
            _uiState.update { it.copy(creating = true, error = null) }
            when (
                val result = conversationRepository.createGroup(
                    title = state.title,
                    memberIds = state.selectedIds.toList(),
                )
            ) {
                is Result.Success -> {
                    _uiState.update { it.copy(creating = false) }
                    _events.send(Event.Created(result.value))
                }
                is Result.Failure -> _uiState.update {
                    it.copy(creating = false, error = result.error)
                }
            }
        }
    }
}
