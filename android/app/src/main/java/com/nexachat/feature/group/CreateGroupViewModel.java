package com.nexachat.feature.group;

import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelKt;
import com.nexachat.data.ContactsRepository;
import com.nexachat.data.ConversationRepository;
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
 * Backs {@code group/create}. Field inputs and member selection live in
 * {@link CreateGroupUiState}; one-shot navigation goes through {@link #getEvents()}.
 */
@HiltViewModel
public final class CreateGroupViewModel extends ViewModel {

    private final ContactsRepository contactsRepository;
    private final ConversationRepository conversationRepository;
    private final MutableStateFlow<CreateGroupUiState> mutableUiState =
            StateFlowKt.MutableStateFlow(new CreateGroupUiState());
    private final StateFlow<CreateGroupUiState> uiState = mutableUiState;
    private final Channel<CreateGroupEvent> eventsChannel =
            CreateGroupViewModelSupportKt.newCreateGroupEventChannel();
    private final Flow<CreateGroupEvent> events =
            CreateGroupViewModelSupportKt.createGroupEventsFlow(eventsChannel);

    @Inject
    public CreateGroupViewModel(
            ContactsRepository contactsRepository, ConversationRepository conversationRepository) {
        this.contactsRepository = Objects.requireNonNull(contactsRepository, "contactsRepository");
        this.conversationRepository =
                Objects.requireNonNull(conversationRepository, "conversationRepository");
        loadContacts();
    }

    public StateFlow<CreateGroupUiState> getUiState() {
        return uiState;
    }

    public Flow<CreateGroupEvent> getEvents() {
        return events;
    }

    public void loadContacts() {
        launch(
                (scope, continuation) ->
                        CreateGroupViewModelSupportKt.createGroupLoadContacts(
                                contactsRepository, mutableUiState, continuation));
    }

    public void onTitleChange(String value) {
        mutableUiState.setValue(mutableUiState.getValue().withTitle(value));
    }

    public void toggleMember(String userId) {
        mutableUiState.setValue(mutableUiState.getValue().toggleMember(userId));
    }

    public void dismissError() {
        mutableUiState.setValue(mutableUiState.getValue().withError(null));
    }

    public void create() {
        launch(
                (scope, continuation) ->
                        CreateGroupViewModelSupportKt.createGroupCreate(
                                conversationRepository,
                                mutableUiState,
                                eventsChannel,
                                continuation));
    }

    private void launch(
            Function2<CoroutineScope, Continuation<? super Unit>, Object> block) {
        BuildersKt.launch(
                ViewModelKt.getViewModelScope(this),
                EmptyCoroutineContext.INSTANCE,
                CoroutineStart.DEFAULT,
                block);
    }
}
