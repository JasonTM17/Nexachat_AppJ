package com.nexachat.feature.group

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.data.Contact
import com.nexachat.data.ContactsRepository
import com.nexachat.data.ConversationRepository
import com.nexachat.data.GroupMember
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Backs `group/{conversationId}/detail` (phase-08 FR-D2/D4). The member list
 * is fetch-only — `ConversationEntity` does not persist members, so
 * [loadMembers] re-derives it from the inbox payload after every mutation.
 * The title also streams from Room via `conversationFlow`, which covers a
 * rename broadcast before the next member fetch.
 *
 * Confirm dialogs are VM state ([UiState.pendingRemove], [UiState.confirmLeave])
 * rather than composable state so the flows are unit-testable. ADMIN-only
 * affordances gate on [UiState.iAmAdmin], which is strictly `role == "ADMIN"`
 * on my own member row — unknown roles hide the actions (best-effort; the
 * server still enforces).
 */
@HiltViewModel
@OptIn(FlowPreview::class)
class GroupDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val conversationRepository: ConversationRepository,
    private val contactsRepository: ContactsRepository,
) : ViewModel() {

    private val conversationId: String =
        savedStateHandle.get<String>("conversationId").orEmpty()

    data class UiState(
        val loading: Boolean = true,
        val title: String = "",
        val members: List<GroupMember> = emptyList(),
        /** Caller's user id — drives self-row labeling and remove-target rules. */
        val myUserId: String? = null,
        /** True only when my member row carries `role == "ADMIN"`. */
        val iAmAdmin: Boolean = false,
        /** Member pending remove confirmation; null = no dialog. */
        val pendingRemove: GroupMember? = null,
        val confirmLeave: Boolean = false,
        // Rename dialog.
        val showRename: Boolean = false,
        val renameInput: String = "",
        // Add-member sheet.
        val showAddPicker: Boolean = false,
        val addableContacts: List<Contact> = emptyList(),
        val selectedAddIds: Set<String> = emptySet(),
        val busy: Boolean = false,
        val error: ApiError? = null,
        /** Current group avatar URL (FR-D4) — null renders the letter fallback. */
        val groupAvatar: String? = null,
    )

    sealed interface Event {
        /** Leave succeeded — the nav host returns to the inbox. */
        data object ExitToInbox : Event
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()
    private val memberRefreshMutex = Mutex()

    init {
        // Room stream keeps the title and avatar live across admin edits.
        viewModelScope.launch {
            conversationRepository.conversationFlow(conversationId).collect { summary ->
                summary?.let { s ->
                    _uiState.update { it.copy(groupAvatar = s.avatarUrl) }
                    s.displayName.takeIf { it.isNotBlank() }?.let { name ->
                        _uiState.update { it.copy(title = name) }
                    }
                }
            }
        }
        viewModelScope.launch {
            conversationRepository.groupUpdates(conversationId)
                // A rename/add/remove fans out one CONVERSATION_UPDATED per
                // affected client within milliseconds; without this window the
                // open detail screen refetched the whole inbox once per push.
                .debounce(PUSH_REFRESH_DEBOUNCE_MS)
                .collect { refreshMembers() }
        }
        loadMembers()
    }

    /** Fetches the member list (and title fallback) from the inbox payload. */
    fun loadMembers() {
        viewModelScope.launch { refreshMembers() }
    }

    private suspend fun refreshMembers() = memberRefreshMutex.withLock {
        _uiState.update { it.copy(loading = true) }
        val myId = conversationRepository.myUserId()
        when (val result = conversationRepository.groupDetail(conversationId)) {
            is Result.Success -> _uiState.update { state ->
                state.copy(
                    loading = false,
                    title = result.value.title
                        ?.takeIf { it.isNotBlank() }
                        ?: state.title,
                    members = result.value.members,
                    myUserId = myId,
                    iAmAdmin = result.value.members.any {
                        it.userId == myId && it.isAdmin
                    },
                )
            }
            is Result.Failure -> _uiState.update {
                it.copy(loading = false, error = result.error)
            }
        }
    }

    /** Called by the UI once a snackbar has been shown for [UiState.error]. */
    fun dismissError() = _uiState.update { it.copy(error = null) }

    // ---- add member ----

    /** Opens the picker with contacts filtered to non-members. */
    fun openAddMember() {
        if (_uiState.value.showAddPicker) return
        _uiState.update {
            it.copy(showAddPicker = true, selectedAddIds = emptySet(), addableContacts = emptyList())
        }
        viewModelScope.launch {
            when (val result = contactsRepository.contacts()) {
                is Result.Success -> _uiState.update { state ->
                    val memberIds = state.members.mapTo(HashSet()) { it.userId }
                    state.copy(
                        addableContacts = result.value.filterNot { it.userId in memberIds },
                    )
                }
                is Result.Failure -> _uiState.update {
                    it.copy(showAddPicker = false, error = result.error)
                }
            }
        }
    }

    fun dismissAddMember() =
        _uiState.update {
            it.copy(showAddPicker = false, selectedAddIds = emptySet(), addableContacts = emptyList())
        }

    fun toggleAddCandidate(userId: String) =
        _uiState.update { state ->
            state.copy(
                selectedAddIds = if (userId in state.selectedAddIds) {
                    state.selectedAddIds - userId
                } else {
                    state.selectedAddIds + userId
                },
            )
        }

    fun addSelectedMembers() {
        val ids = _uiState.value.selectedAddIds.toList()
        if (ids.isEmpty() || _uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            when (val result = conversationRepository.addGroupMembers(conversationId, ids)) {
                is Result.Success -> {
                    _uiState.update {
                        it.copy(
                            busy = false,
                            showAddPicker = false,
                            selectedAddIds = emptySet(),
                            addableContacts = emptyList(),
                        )
                    }
                    loadMembers()
                }
                is Result.Failure -> _uiState.update {
                    it.copy(busy = false, error = result.error)
                }
            }
        }
    }

    // ---- remove member ----

    fun requestRemoveMember(member: GroupMember) =
        _uiState.update { it.copy(pendingRemove = member) }

    fun dismissRemoveMember() = _uiState.update { it.copy(pendingRemove = null) }

    /** Runs the confirmed remove for [UiState.pendingRemove], then refetches. */
    fun confirmRemoveMember() {
        val member = _uiState.value.pendingRemove ?: return
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, pendingRemove = null) }
            when (val result = conversationRepository.removeGroupMember(conversationId, member.userId)) {
                is Result.Success -> {
                    _uiState.update { it.copy(busy = false) }
                    loadMembers()
                }
                is Result.Failure -> _uiState.update {
                    it.copy(busy = false, error = result.error)
                }
            }
        }
    }

    // ---- rename ----

    fun openRename() {
        _uiState.update { it.copy(showRename = true, renameInput = it.title) }
    }

    fun dismissRename() = _uiState.update { it.copy(showRename = false, renameInput = "") }

    fun onRenameInputChange(value: String) =
        _uiState.update { it.copy(renameInput = value) }

    fun confirmRename() {
        val title = _uiState.value.renameInput.trim()
        if (title.isEmpty() || _uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            when (val result = conversationRepository.renameGroup(conversationId, title)) {
                is Result.Success -> _uiState.update {
                    it.copy(busy = false, showRename = false, renameInput = "")
                }
                is Result.Failure -> _uiState.update {
                    it.copy(busy = false, error = result.error, showRename = true)
                }
            }
        }
    }

    /**
     * Group avatar pick → `POST /conversations/{id}/avatar` (ADMIN-only
     * server-side, FR-D4). The repo patches the Room row on success so the
     * header re-renders without waiting for CONVERSATION_UPDATED.
     */
    fun setGroupAvatar(uri: Uri) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            when (val result = conversationRepository.setGroupAvatar(conversationId, uri)) {
                is Result.Success -> _uiState.update { it.copy(busy = false) }
                is Result.Failure -> _uiState.update {
                    it.copy(busy = false, error = result.error)
                }
            }
        }
    }

    // ---- leave ----

    fun requestLeave() = _uiState.update { it.copy(confirmLeave = true) }

    fun dismissLeave() = _uiState.update { it.copy(confirmLeave = false) }

    /** Confirmed leave → [Event.ExitToInbox]; failure lands in state as a snackbar. */
    fun confirmLeave() {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, confirmLeave = false) }
            when (val result = conversationRepository.leaveGroup(conversationId)) {
                is Result.Success -> _events.send(Event.ExitToInbox)
                is Result.Failure -> _uiState.update {
                    it.copy(busy = false, error = result.error)
                }
            }
        }
    }

    private companion object {
        /**
         * Quiet window that folds a member/rename/avatar broadcast burst into
         * one inbox fetch. Short enough that the open screen still feels live.
         */
        const val PUSH_REFRESH_DEBOUNCE_MS = 200L
    }
}
