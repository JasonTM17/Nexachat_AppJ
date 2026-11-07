package com.nexachat.feature.group

import android.net.Uri
import com.nexachat.core.common.Result
import com.nexachat.data.ContactsRepository
import com.nexachat.data.ConversationRepository
import com.nexachat.data.GroupMember
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val GROUP_PUSH_REFRESH_DEBOUNCE_MS = 200L

internal fun newGroupDetailEventChannel(): Channel<GroupDetailEvent> = Channel(Channel.BUFFERED)

internal fun groupDetailEvents(channel: Channel<GroupDetailEvent>): Flow<GroupDetailEvent> =
    channel.receiveAsFlow()

internal class GroupDetailSession(
    private val conversationId: String,
    private val conversationRepository: ConversationRepository,
    private val contactsRepository: ContactsRepository,
    private val uiState: MutableStateFlow<GroupDetailUiState>,
    private val events: Channel<GroupDetailEvent>,
) {
    private val memberRefreshMutex = Mutex()

    @OptIn(FlowPreview::class)
    suspend fun collectConversation() {
        conversationRepository.conversationFlow(conversationId).collect { summary ->
            summary?.let { row ->
                uiState.update { it.withGroupAvatar(row.avatarUrl) }
                row.displayName.takeIf { it.isNotBlank() }?.let { name ->
                    uiState.update { it.withTitle(name) }
                }
            }
        }
    }

    @OptIn(FlowPreview::class)
    suspend fun collectGroupUpdates() {
        conversationRepository.groupUpdates(conversationId)
            .debounce(GROUP_PUSH_REFRESH_DEBOUNCE_MS)
            .collect { refreshMembers() }
    }

    suspend fun refreshMembers() = memberRefreshMutex.withLock {
        uiState.update { it.withLoading(true) }
        val myId = conversationRepository.myUserId()
        when (val result = conversationRepository.groupDetail(conversationId)) {
            is Result.Success -> uiState.update { state ->
                val title = result.value.title?.takeIf { it.isNotBlank() } ?: state.title
                state.withMembers(
                    title,
                    result.value.members,
                    myId,
                    result.value.members.any { it.userId == myId && it.isAdmin },
                )
            }
            is Result.Failure -> uiState.update { it.withLoadFailure(result.error) }
        }
    }

    suspend fun openAddMember() {
        if (uiState.value.showAddPicker) return
        uiState.update { it.withAddPickerOpen() }
        when (val result = contactsRepository.contacts()) {
            is Result.Success -> uiState.update { state ->
                val memberIds = state.members.mapTo(HashSet()) { it.userId }
                state.withAddableContacts(result.value.filterNot { it.userId in memberIds })
            }
            is Result.Failure -> uiState.update { it.withAddPickerClosedOnError(result.error) }
        }
    }

    suspend fun addSelectedMembers() {
        val ids = uiState.value.selectedAddIds.toList()
        if (ids.isEmpty() || uiState.value.busy) return
        uiState.update { it.withBusy(true) }
        when (val result = conversationRepository.addGroupMembers(conversationId, ids)) {
            is Result.Success -> {
                uiState.update { it.withMembersAdded() }
                refreshMembers()
            }
            is Result.Failure -> uiState.update { it.withBusyAndError(false, result.error) }
        }
    }

    suspend fun confirmRemoveMember() {
        val member: GroupMember = uiState.value.pendingRemove ?: return
        if (uiState.value.busy) return
        uiState.update { it.withRemoveInFlight() }
        when (val result = conversationRepository.removeGroupMember(conversationId, member.userId)) {
            is Result.Success -> {
                uiState.update { it.withBusy(false) }
                refreshMembers()
            }
            is Result.Failure -> uiState.update { it.withBusyAndError(false, result.error) }
        }
    }

    suspend fun confirmRename() {
        val title = uiState.value.renameInput.trim()
        if (title.isEmpty() || uiState.value.busy) return
        uiState.update { it.withBusy(true) }
        when (val result = conversationRepository.renameGroup(conversationId, title)) {
            is Result.Success -> uiState.update { it.withRenameSucceeded() }
            is Result.Failure -> uiState.update { it.withRenameFailed(result.error) }
        }
    }

    suspend fun setGroupAvatar(uri: Uri) {
        if (uiState.value.busy) return
        uiState.update { it.withBusy(true) }
        when (val result = conversationRepository.setGroupAvatar(conversationId, uri)) {
            is Result.Success -> uiState.update { it.withBusy(false) }
            is Result.Failure -> uiState.update { it.withBusyAndError(false, result.error) }
        }
    }

    suspend fun confirmLeave() {
        if (uiState.value.busy) return
        uiState.update { it.withLeaveInFlight() }
        when (val result = conversationRepository.leaveGroup(conversationId)) {
            is Result.Success -> events.send(GroupDetailEvent.ExitToInbox.INSTANCE)
            is Result.Failure -> uiState.update { it.withBusyAndError(false, result.error) }
        }
    }
}
