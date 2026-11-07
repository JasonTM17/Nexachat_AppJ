package com.nexachat.feature.group;

import android.net.Uri;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelKt;
import com.nexachat.data.ContactsRepository;
import com.nexachat.data.ConversationRepository;
import com.nexachat.data.GroupMember;
import dagger.hilt.android.lifecycle.HiltViewModel;
import java.util.Objects;
import javax.inject.Inject;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineStart;
import kotlinx.coroutines.channels.Channel;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

/**
 * Backs group detail. Member refresh is serialized; title and avatar follow the
 * Room conversation stream.
 */
@HiltViewModel
public final class GroupDetailViewModel extends ViewModel {

    private final MutableStateFlow<GroupDetailUiState> mutableUiState =
            StateFlowKt.MutableStateFlow(new GroupDetailUiState());
    private final StateFlow<GroupDetailUiState> uiState = mutableUiState;
    private final Channel<GroupDetailEvent> eventsChannel =
            GroupDetailViewModelSupportKt.newGroupDetailEventChannel();
    private final Flow<GroupDetailEvent> events =
            GroupDetailViewModelSupportKt.groupDetailEvents(eventsChannel);
    private final GroupDetailSession session;

    @Inject
    public GroupDetailViewModel(
            SavedStateHandle savedStateHandle,
            ConversationRepository conversationRepository,
            ContactsRepository contactsRepository) {
        String conversationId = savedStateHandle.get("conversationId");
        if (conversationId == null) {
            conversationId = "";
        }
        this.session = new GroupDetailSession(
                conversationId,
                Objects.requireNonNull(conversationRepository, "conversationRepository"),
                Objects.requireNonNull(contactsRepository, "contactsRepository"),
                mutableUiState,
                eventsChannel);
        launch((scope, continuation) -> session.collectConversation(continuation));
        launch((scope, continuation) -> session.collectGroupUpdates(continuation));
        loadMembers();
    }

    public StateFlow<GroupDetailUiState> getUiState() {
        return uiState;
    }

    public Flow<GroupDetailEvent> getEvents() {
        return events;
    }

    public void loadMembers() {
        launch((scope, continuation) -> session.refreshMembers(continuation));
    }

    public void dismissError() {
        mutableUiState.setValue(mutableUiState.getValue().withError(null));
    }

    public void openAddMember() {
        launch((scope, continuation) -> session.openAddMember(continuation));
    }

    public void dismissAddMember() {
        mutableUiState.setValue(mutableUiState.getValue().withAddPickerDismissed());
    }

    public void toggleAddCandidate(String userId) {
        mutableUiState.setValue(mutableUiState.getValue().toggleAddCandidate(userId));
    }

    public void addSelectedMembers() {
        launch((scope, continuation) -> session.addSelectedMembers(continuation));
    }

    public void requestRemoveMember(GroupMember member) {
        mutableUiState.setValue(mutableUiState.getValue().withPendingRemove(member));
    }

    public void dismissRemoveMember() {
        mutableUiState.setValue(mutableUiState.getValue().withPendingRemove(null));
    }

    public void confirmRemoveMember() {
        launch((scope, continuation) -> session.confirmRemoveMember(continuation));
    }

    public void openRename() {
        mutableUiState.setValue(mutableUiState.getValue().withRenameOpen());
    }

    public void dismissRename() {
        mutableUiState.setValue(mutableUiState.getValue().withRenameDismissed());
    }

    public void onRenameInputChange(String value) {
        mutableUiState.setValue(mutableUiState.getValue().withRenameInput(value));
    }

    public void confirmRename() {
        launch((scope, continuation) -> session.confirmRename(continuation));
    }

    public void setGroupAvatar(Uri uri) {
        launch((scope, continuation) -> session.setGroupAvatar(uri, continuation));
    }

    public void requestLeave() {
        mutableUiState.setValue(mutableUiState.getValue().withConfirmLeave(true));
    }

    public void dismissLeave() {
        mutableUiState.setValue(mutableUiState.getValue().withConfirmLeave(false));
    }

    public void confirmLeave() {
        launch((scope, continuation) -> session.confirmLeave(continuation));
    }

    private void launch(Function2<CoroutineScope, Continuation<? super Unit>, Object> block) {
        BuildersKt.launch(
                ViewModelKt.getViewModelScope(this),
                EmptyCoroutineContext.INSTANCE,
                CoroutineStart.DEFAULT,
                block);
    }
}
