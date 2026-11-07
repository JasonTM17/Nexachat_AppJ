package com.nexachat.feature.group;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.data.Contact;
import com.nexachat.data.ContactsRepository;
import com.nexachat.data.ConversationRepository;
import com.nexachat.data.ConversationSummary;
import com.nexachat.data.GroupDetail;
import com.nexachat.data.GroupMember;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.processors.PublishProcessor;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;

/** Backs group detail. Member refreshes are serialized; Room owns title/avatar updates. */
@HiltViewModel
public final class GroupDetailViewModel extends ViewModel {

    private final String conversationId;
    private final ConversationRepository conversationRepository;
    private final ContactsRepository contactsRepository;
    private final MutableLiveData<GroupDetailUiState> mutableUiState =
            new MutableLiveData<>(new GroupDetailUiState());
    private final GroupSingleLiveEvent<GroupDetailEvent> events = new GroupSingleLiveEvent<>();
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final PublishProcessor<Boolean> refreshRequests = PublishProcessor.create();

    @Inject
    public GroupDetailViewModel(
            SavedStateHandle savedStateHandle,
            ConversationRepository conversationRepository,
            ContactsRepository contactsRepository) {
        String savedId = savedStateHandle.get("conversationId");
        this.conversationId = savedId == null ? "" : savedId;
        this.conversationRepository = Objects.requireNonNull(
                conversationRepository, "conversationRepository");
        this.contactsRepository = Objects.requireNonNull(contactsRepository, "contactsRepository");
        observeConversation();
        observeGroupUpdates();
        serializeMemberRefreshes();
        loadMembers();
    }

    public LiveData<GroupDetailUiState> getUiState() {
        return mutableUiState;
    }

    public LiveData<GroupSingleLiveEvent.Event<GroupDetailEvent>> getEvents() {
        return events;
    }

    private void observeConversation() {
        disposables.add(conversationRepository.conversationFlow(conversationId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(summary -> summary.ifPresent(this::applyConversation), this::surfaceError));
    }

    private void observeGroupUpdates() {
        disposables.add(conversationRepository.groupUpdates(conversationId)
                .subscribeOn(Schedulers.io())
                .debounce(GroupDetailViewModelSupport.GROUP_PUSH_REFRESH_DEBOUNCE_MS,
                        TimeUnit.MILLISECONDS, Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(changed -> {
                    if (changed) {
                        loadMembers();
                    }
                }, this::surfaceError));
    }

    private void serializeMemberRefreshes() {
        disposables.add(refreshRequests.onBackpressureBuffer()
                .concatMapSingle(ignored -> fetchMembers())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::applyMemberLoad, this::surfaceError));
    }

    private void applyConversation(ConversationSummary summary) {
        if (summary == null) {
            return;
        }
        GroupDetailUiState state = currentState().withGroupAvatar(summary.getAvatarUrl());
        String title = summary.getDisplayName();
        if (title != null && !title.isBlank()) {
            state = state.withTitle(title);
        }
        mutableUiState.setValue(state);
    }

    private Single<MemberLoad> fetchMembers() {
        return conversationRepository.myUserId()
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(myId -> conversationRepository.groupDetail(conversationId)
                        .map(result -> new MemberLoad(myId.orElse(null), result)))
                .onErrorReturn(error -> new MemberLoad(null,
                        Result.failure(new ApiError.Network(error.getMessage()))))
                .subscribeOn(Schedulers.io());
    }

    private void applyMemberLoad(MemberLoad load) {
        Result<GroupDetail> result = load.result;
        if (result instanceof Result.Success<?> success
                && success.getValue() instanceof GroupDetail detail) {
            GroupDetailUiState state = currentState();
            String title = GroupDetailViewModelSupport.titleOrCurrent(
                    detail.getTitle(), state.getTitle());
            boolean isAdmin = GroupDetailViewModelSupport.isAdmin(detail.getMembers(), load.myUserId);
            mutableUiState.setValue(state.withMembers(title, detail.getMembers(), load.myUserId, isAdmin));
        } else if (result instanceof Result.Failure<?> failure) {
            mutableUiState.setValue(currentState().withLoadFailure(failure.getError()));
        }
    }

    public void loadMembers() {
        mutableUiState.setValue(currentState().withLoading(true));
        refreshRequests.onNext(Boolean.TRUE);
    }

    public void dismissError() {
        mutableUiState.setValue(currentState().withError(null));
    }

    public void openAddMember() {
        GroupDetailUiState state = currentState();
        if (state.isShowAddPicker()) {
            return;
        }
        mutableUiState.setValue(state.withAddPickerOpen());
        disposables.add(contactsRepository.contacts()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (result instanceof Result.Success<?> success
                            && success.getValue() instanceof List<?> contacts) {
                        @SuppressWarnings("unchecked")
                        List<Contact> contactList = (List<Contact>) contacts;
                        List<Contact> addable = GroupDetailViewModelSupport.addableContacts(
                                contactList, currentState().getMembers());
                        mutableUiState.setValue(currentState().withAddableContacts(addable));
                    } else if (result instanceof Result.Failure<?> failure) {
                        mutableUiState.setValue(currentState()
                                .withAddPickerClosedOnError(failure.getError()));
                    }
                }, this::surfaceError));
    }

    public void dismissAddMember() {
        mutableUiState.setValue(currentState().withAddPickerDismissed());
    }

    public void toggleAddCandidate(String userId) {
        mutableUiState.setValue(currentState().toggleAddCandidate(userId));
    }

    public void addSelectedMembers() {
        GroupDetailUiState state = currentState();
        List<String> ids = List.copyOf(state.getSelectedAddIds());
        if (ids.isEmpty() || state.isBusy()) {
            return;
        }
        mutableUiState.setValue(state.withBusy(true));
        disposables.add(conversationRepository.addGroupMembers(conversationId, ids)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (result instanceof Result.Success<?>) {
                        mutableUiState.setValue(currentState().withMembersAdded());
                        loadMembers();
                    } else if (result instanceof Result.Failure<?> failure) {
                        mutableUiState.setValue(currentState()
                                .withBusyAndError(false, failure.getError()));
                    }
                }, this::surfaceError));
    }

    public void requestRemoveMember(GroupMember member) {
        mutableUiState.setValue(currentState().withPendingRemove(member));
    }

    public void dismissRemoveMember() {
        mutableUiState.setValue(currentState().withPendingRemove(null));
    }

    public void confirmRemoveMember() {
        GroupDetailUiState state = currentState();
        GroupMember member = state.getPendingRemove();
        if (member == null || state.isBusy()) {
            return;
        }
        mutableUiState.setValue(state.withRemoveInFlight());
        disposables.add(conversationRepository.removeGroupMember(
                        conversationId, member.getUserId())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (result instanceof Result.Success<?>) {
                        mutableUiState.setValue(currentState().withBusy(false));
                        loadMembers();
                    } else if (result instanceof Result.Failure<?> failure) {
                        mutableUiState.setValue(currentState()
                                .withBusyAndError(false, failure.getError()));
                    }
                }, this::surfaceError));
    }

    public void openRename() {
        mutableUiState.setValue(currentState().withRenameOpen());
    }

    public void dismissRename() {
        mutableUiState.setValue(currentState().withRenameDismissed());
    }

    public void onRenameInputChange(String value) {
        mutableUiState.setValue(currentState().withRenameInput(value));
    }

    public void confirmRename() {
        GroupDetailUiState state = currentState();
        String title = state.getRenameInput().trim();
        if (title.isEmpty() || state.isBusy()) {
            return;
        }
        mutableUiState.setValue(state.withBusy(true));
        disposables.add(conversationRepository.renameGroup(conversationId, title)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (result instanceof Result.Success<?>) {
                        mutableUiState.setValue(currentState().withRenameSucceeded());
                    } else if (result instanceof Result.Failure<?> failure) {
                        mutableUiState.setValue(currentState().withRenameFailed(failure.getError()));
                    }
                }, this::surfaceError));
    }

    public void setGroupAvatar(Uri uri) {
        GroupDetailUiState state = currentState();
        if (state.isBusy()) {
            return;
        }
        mutableUiState.setValue(state.withBusy(true));
        disposables.add(conversationRepository.setGroupAvatar(conversationId, uri)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (result instanceof Result.Success<?>) {
                        mutableUiState.setValue(currentState().withBusy(false));
                    } else if (result instanceof Result.Failure<?> failure) {
                        mutableUiState.setValue(currentState()
                                .withBusyAndError(false, failure.getError()));
                    }
                }, this::surfaceError));
    }

    public void requestLeave() {
        mutableUiState.setValue(currentState().withConfirmLeave(true));
    }

    public void dismissLeave() {
        mutableUiState.setValue(currentState().withConfirmLeave(false));
    }

    public void confirmLeave() {
        if (currentState().isBusy()) {
            return;
        }
        mutableUiState.setValue(currentState().withLeaveInFlight());
        disposables.add(conversationRepository.leaveGroup(conversationId)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (result instanceof Result.Success<?>) {
                        events.emit(GroupDetailEvent.ExitToInbox.INSTANCE);
                    } else if (result instanceof Result.Failure<?> failure) {
                        mutableUiState.setValue(currentState()
                                .withBusyAndError(false, failure.getError()));
                    }
                }, this::surfaceError));
    }

    private GroupDetailUiState currentState() {
        GroupDetailUiState state = mutableUiState.getValue();
        return state == null ? new GroupDetailUiState() : state;
    }

    private void surfaceError(Throwable error) {
        mutableUiState.setValue(currentState().withBusyAndError(false,
                new ApiError.Network(error.getMessage())));
    }

    @Override
    protected void onCleared() {
        disposables.clear();
        refreshRequests.onComplete();
        super.onCleared();
    }

    private static final class MemberLoad {
        @Nullable private final String myUserId;
        private final Result<GroupDetail> result;

        private MemberLoad(@Nullable String myUserId, Result<GroupDetail> result) {
            this.myUserId = myUserId;
            this.result = result;
        }
    }
}
