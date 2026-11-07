package com.nexachat.feature.group;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.data.Contact;
import com.nexachat.data.ContactsRepository;
import com.nexachat.data.ConversationRepository;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.List;
import java.util.Objects;
import javax.inject.Inject;

/** Backs {@code group/create}; form state is immutable and navigation is one-shot. */
@HiltViewModel
public final class CreateGroupViewModel extends ViewModel {

    private final ContactsRepository contactsRepository;
    private final ConversationRepository conversationRepository;
    private final MutableLiveData<CreateGroupUiState> mutableUiState =
            new MutableLiveData<>(new CreateGroupUiState());
    private final GroupSingleLiveEvent<CreateGroupEvent> events = new GroupSingleLiveEvent<>();
    private final CompositeDisposable disposables = new CompositeDisposable();

    @Inject
    public CreateGroupViewModel(
            ContactsRepository contactsRepository,
            ConversationRepository conversationRepository) {
        this.contactsRepository = Objects.requireNonNull(contactsRepository, "contactsRepository");
        this.conversationRepository = Objects.requireNonNull(
                conversationRepository, "conversationRepository");
        loadContacts();
    }

    public LiveData<CreateGroupUiState> getUiState() {
        return mutableUiState;
    }

    public LiveData<GroupSingleLiveEvent.Event<CreateGroupEvent>> getEvents() {
        return events;
    }

    public void loadContacts() {
        mutableUiState.setValue(currentState().withLoading(true, null));
        disposables.add(contactsRepository.contacts()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (result instanceof Result.Success<?> success
                            && success.getValue() instanceof List<?> contacts) {
                        @SuppressWarnings("unchecked")
                        List<Contact> contactList = (List<Contact>) contacts;
                        mutableUiState.setValue(currentState().withContacts(contactList, true));
                    } else if (result instanceof Result.Failure<?> failure) {
                        mutableUiState.setValue(currentState().withContactsFailure(failure.getError()));
                    }
                }, error -> mutableUiState.setValue(currentState().withContactsFailure(
                        new ApiError.Network(error.getMessage())))));
    }

    public void onTitleChange(String value) {
        mutableUiState.setValue(currentState().withTitle(value));
    }

    public void toggleMember(String userId) {
        mutableUiState.setValue(currentState().toggleMember(userId));
    }

    public void dismissError() {
        mutableUiState.setValue(currentState().withError(null));
    }

    public void create() {
        CreateGroupUiState state = currentState();
        if (!CreateGroupViewModelSupport.canCreate(state)) {
            return;
        }
        mutableUiState.setValue(state.withCreating(true, null));
        List<String> selectedIds = CreateGroupViewModelSupport.selectedIds(state.getSelectedIds());
        disposables.add(conversationRepository.createGroup(state.getTitle(), selectedIds)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    if (result instanceof Result.Success<?> success
                            && success.getValue() instanceof String conversationId) {
                        mutableUiState.setValue(currentState().withCreating(false, null));
                        events.emit(new CreateGroupEvent.Created(conversationId));
                    } else if (result instanceof Result.Failure<?> failure) {
                        mutableUiState.setValue(currentState()
                                .withCreating(false, failure.getError()));
                    }
                }, error -> mutableUiState.setValue(currentState().withCreating(
                        false, new ApiError.Network(error.getMessage())))));
    }

    private CreateGroupUiState currentState() {
        CreateGroupUiState state = mutableUiState.getValue();
        return state == null ? new CreateGroupUiState() : state;
    }

    @Override
    protected void onCleared() {
        disposables.clear();
        super.onCleared();
    }
}
