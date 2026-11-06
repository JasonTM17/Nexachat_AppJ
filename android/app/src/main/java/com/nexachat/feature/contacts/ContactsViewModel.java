package com.nexachat.feature.contacts;

import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelKt;
import androidx.annotation.Nullable;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.data.Contact;
import com.nexachat.data.ContactsRepository;
import dagger.hilt.android.lifecycle.HiltViewModel;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
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
 * Backs ContactsScreen and UserSearchScreen. Each nav destination owns a
 * separate instance; contact rows are combined from the repository's shared
 * cache so changes from either screen appear in both.
 */
@HiltViewModel
public final class ContactsViewModel extends ViewModel {

    private static final String KEY_SEARCH_QUERY = "search_query";

    private final SavedStateHandle savedStateHandle;
    private final ContactsRepository contactsRepository;
    private final MutableStateFlow<UiState> mutableUiState;
    private final StateFlow<UiState> uiState;
    private final Channel<Event> eventsChannel =
            ContactsViewModelSupportKt.newContactsEventChannel();
    private final Flow<Event> events =
            ContactsViewModelSupportKt.contactsEventsFlow(eventsChannel);

    @Inject
    public ContactsViewModel(
            SavedStateHandle savedStateHandle,
            ContactsRepository contactsRepository) {
        this.savedStateHandle =
                Objects.requireNonNull(savedStateHandle, "savedStateHandle");
        this.contactsRepository =
                Objects.requireNonNull(contactsRepository, "contactsRepository");
        String restoredQuery = savedStateHandle.get(KEY_SEARCH_QUERY);
        mutableUiState = StateFlowKt.MutableStateFlow(
                new UiState().withQuery(restoredQuery == null ? "" : restoredQuery));
        uiState = ContactsViewModelSupportKt.contactsUiState(
                mutableUiState,
                contactsRepository.getContactsFlow(),
                ViewModelKt.getViewModelScope(this));
        loadContacts();
    }

    public StateFlow<UiState> getUiState() {
        return uiState;
    }

    public Flow<Event> getEvents() {
        return events;
    }

    /** Loads the shared contact list once for this ViewModel instance. */
    public void loadContacts() {
        launch((scope, continuation) ->
                ContactsViewModelSupportKt.contactsLoadContacts(
                        contactsRepository, mutableUiState, continuation));
    }

    public void onQueryChange(String value) {
        Objects.requireNonNull(value, "value");
        savedStateHandle.set(KEY_SEARCH_QUERY, value);
        UiState state = mutableUiState.getValue();
        mutableUiState.setValue(state
                .withQuery(value)
                .withError(null)
                .withSearched(state.isSearched() && hasNonWhitespace(value)));
    }

    /** True when a query begins with '+' or a Unicode digit. */
    private static boolean isPhoneQuery(String query) {
        if (query.isEmpty()) {
            return false;
        }
        char first = query.charAt(0);
        return first == '+' || Character.isDigit(first);
    }

    /**
     * Explicit submit only: phone input uses exact lookup and other input uses
     * name-prefix search because the endpoint is rate-limited.
     */
    public void search() {
        String query = trimWhitespace(mutableUiState.getValue().getQuery());
        if (query.isEmpty() || mutableUiState.getValue().isLoading()) {
            return;
        }
        launch((scope, continuation) ->
                ContactsViewModelSupportKt.contactsSearch(
                        contactsRepository,
                        mutableUiState,
                        query,
                        isPhoneQuery(query),
                        continuation));
    }

    public void onAddPhoneChange(String value) {
        Objects.requireNonNull(value, "value");
        mutableUiState.setValue(mutableUiState.getValue()
                .withAddPhone(value)
                .withAddError(null)
                .withAddResult(null)
                .withAddDone(false));
    }

    /** Finds a user for confirmation before adding by phone. */
    public void findUserForAdd() {
        String phone = trimWhitespace(mutableUiState.getValue().getAddPhone());
        if (phone.isEmpty() || mutableUiState.getValue().isAddSearching()) {
            return;
        }
        launch((scope, continuation) ->
                ContactsViewModelSupportKt.contactsFindUserForAdd(
                        contactsRepository, mutableUiState, phone, continuation));
    }

    /** Adds the confirmed user, or retries from the entered phone. */
    public void confirmAdd() {
        UiState state = mutableUiState.getValue();
        String phone = state.getAddResult() == null
                ? trimWhitespace(state.getAddPhone())
                : state.getAddResult().getPhone();
        if (phone.isEmpty() || state.isAdding()) {
            return;
        }
        launch((scope, continuation) ->
                ContactsViewModelSupportKt.contactsConfirmAdd(
                        contactsRepository, mutableUiState, phone, continuation));
    }

    /** Resets the add-by-phone flow when its sheet is dismissed. */
    public void resetAddFlow() {
        mutableUiState.setValue(mutableUiState.getValue()
                .withAddPhone("")
                .withAddSearching(false)
                .withAddResult(null)
                .withAdding(false)
                .withAddDone(false)
                .withAddError(null));
    }

    public void openUserDetail(UserDto user) {
        Objects.requireNonNull(user, "user");
        Contact known = null;
        List<Contact> cached = contactsRepository.getContactsFlow().getValue();
        if (cached != null) {
            for (Contact contact : cached) {
                if (contact.getUserId().equals(user.getId())) {
                    known = contact;
                    break;
                }
            }
        }
        mutableUiState.setValue(mutableUiState.getValue()
                .withDetailUser(user)
                .withDetailContact(known)
                .withAliasInput(known == null || known.getAliasName() == null
                        ? ""
                        : known.getAliasName())
                .withDetailError(null));
    }

    public void openContactDetail(Contact contact) {
        Objects.requireNonNull(contact, "contact");
        UserDto user = new UserDto(
                contact.getUserId(),
                contact.getPhone(),
                contact.getDisplayName(),
                null);
        mutableUiState.setValue(mutableUiState.getValue()
                .withDetailUser(user)
                .withDetailContact(contact)
                .withAliasInput(contact.getAliasName() == null ? "" : contact.getAliasName())
                .withDetailError(null));
    }

    public void closeDetail() {
        mutableUiState.setValue(mutableUiState.getValue()
                .withDetailUser(null)
                .withDetailContact(null)
                .withAliasInput("")
                .withDetailBusy(false)
                .withDetailError(null));
    }

    public void onAliasChange(String value) {
        Objects.requireNonNull(value, "value");
        mutableUiState.setValue(mutableUiState.getValue()
                .withAliasInput(value)
                .withDetailError(null));
    }

    /** Persists the selected contact's alias; blank input clears it. */
    public void saveAlias() {
        UiState state = mutableUiState.getValue();
        Contact contact = state.getDetailContact();
        if (contact == null || state.isDetailBusy()) {
            return;
        }
        String alias = trimWhitespace(state.getAliasInput());
        if (!hasNonWhitespace(alias)) {
            alias = null;
        }
        String requestedAlias = alias;
        launch((scope, continuation) ->
                ContactsViewModelSupportKt.contactsSaveAlias(
                        contactsRepository,
                        mutableUiState,
                        contact,
                        requestedAlias,
                        continuation));
    }

    public void removeSelectedContact() {
        UiState state = mutableUiState.getValue();
        Contact contact = state.getDetailContact();
        if (contact == null || state.isDetailBusy()) {
            return;
        }
        launch((scope, continuation) ->
                ContactsViewModelSupportKt.contactsRemoveSelectedContact(
                        contactsRepository, mutableUiState, contact, continuation));
    }

    /** Adds the selected search result as a contact. */
    public void addDetailContact() {
        UiState state = mutableUiState.getValue();
        UserDto user = state.getDetailUser();
        if (user == null || state.isDetailBusy()) {
            return;
        }
        launch((scope, continuation) ->
                ContactsViewModelSupportKt.contactsAddDetailContact(
                        contactsRepository, mutableUiState, user, continuation));
    }

    /** Opens the selected user's direct conversation and emits navigation. */
    public void messageSelected() {
        UiState state = mutableUiState.getValue();
        UserDto user = state.getDetailUser();
        if (user == null || state.isDetailBusy()) {
            return;
        }
        launch((scope, continuation) ->
                ContactsViewModelSupportKt.contactsMessageSelected(
                        contactsRepository, mutableUiState, eventsChannel, user, continuation));
    }

    private void launch(
            Function2<CoroutineScope, Continuation<? super Unit>, Object> block) {
        BuildersKt.launch(
                ViewModelKt.getViewModelScope(this),
                EmptyCoroutineContext.INSTANCE,
                CoroutineStart.DEFAULT,
                block);
    }

    private static boolean hasNonWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (!Character.isWhitespace(current) && !Character.isSpaceChar(current)) {
                return true;
            }
        }
        return false;
    }

    private static String trimWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isWhitespace(value.charAt(start))) {
            start++;
        }
        while (start < end && isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isWhitespace(char value) {
        return Character.isWhitespace(value) || Character.isSpaceChar(value);
    }

    /** Immutable render state shared by the contacts and user-search screens. */
    public static final class UiState {

        private final String query;
        private final List<UserDto> searchResults;
        private final boolean searched;
        private final List<Contact> contacts;
        private final boolean contactsLoaded;
        private final boolean loading;
        @Nullable private final ApiError error;
        private final String addPhone;
        private final boolean addSearching;
        @Nullable private final UserDto addResult;
        private final boolean adding;
        private final boolean addDone;
        @Nullable private final ApiError addError;
        @Nullable private final UserDto detailUser;
        @Nullable private final Contact detailContact;
        private final String aliasInput;
        private final boolean detailBusy;
        @Nullable private final ApiError detailError;

        public UiState() {
            this(new Builder());
        }

        private UiState(Builder builder) {
            query = Objects.requireNonNull(builder.query, "query");
            searchResults = Objects.requireNonNull(builder.searchResults, "searchResults");
            searched = builder.searched;
            contacts = Objects.requireNonNull(builder.contacts, "contacts");
            contactsLoaded = builder.contactsLoaded;
            loading = builder.loading;
            error = builder.error;
            addPhone = Objects.requireNonNull(builder.addPhone, "addPhone");
            addSearching = builder.addSearching;
            addResult = builder.addResult;
            adding = builder.adding;
            addDone = builder.addDone;
            addError = builder.addError;
            detailUser = builder.detailUser;
            detailContact = builder.detailContact;
            aliasInput = Objects.requireNonNull(builder.aliasInput, "aliasInput");
            detailBusy = builder.detailBusy;
            detailError = builder.detailError;
        }

        private UiState change(Consumer<Builder> update) {
            Builder builder = new Builder(this);
            update.accept(builder);
            return new UiState(builder);
        }

        public String getQuery() { return query; }
        public List<UserDto> getSearchResults() { return searchResults; }
        public boolean isSearched() { return searched; }
        public boolean getSearched() { return searched; }
        public List<Contact> getContacts() { return contacts; }
        public boolean isContactsLoaded() { return contactsLoaded; }
        public boolean getContactsLoaded() { return contactsLoaded; }
        public boolean isLoading() { return loading; }
        public boolean getLoading() { return loading; }
        @Nullable public ApiError getError() { return error; }
        public String getAddPhone() { return addPhone; }
        public boolean isAddSearching() { return addSearching; }
        public boolean getAddSearching() { return addSearching; }
        @Nullable public UserDto getAddResult() { return addResult; }
        public boolean isAdding() { return adding; }
        public boolean getAdding() { return adding; }
        public boolean isAddDone() { return addDone; }
        public boolean getAddDone() { return addDone; }
        @Nullable public ApiError getAddError() { return addError; }
        @Nullable public UserDto getDetailUser() { return detailUser; }
        @Nullable public Contact getDetailContact() { return detailContact; }
        public String getAliasInput() { return aliasInput; }
        public boolean isDetailBusy() { return detailBusy; }
        public boolean getDetailBusy() { return detailBusy; }
        @Nullable public ApiError getDetailError() { return detailError; }

        public UiState withQuery(String value) {
            return change(builder -> builder.query = value);
        }

        public UiState withSearchResults(List<UserDto> value) {
            return change(builder -> builder.searchResults = value);
        }

        public UiState withSearched(boolean value) {
            return change(builder -> builder.searched = value);
        }

        public UiState withContacts(List<Contact> value) {
            return change(builder -> builder.contacts = value);
        }

        public UiState withContactsLoaded(boolean value) {
            return change(builder -> builder.contactsLoaded = value);
        }

        public UiState withLoading(boolean value) {
            return change(builder -> builder.loading = value);
        }

        public UiState withError(@Nullable ApiError value) {
            return change(builder -> builder.error = value);
        }

        public UiState withAddPhone(String value) {
            return change(builder -> builder.addPhone = value);
        }

        public UiState withAddSearching(boolean value) {
            return change(builder -> builder.addSearching = value);
        }

        public UiState withAddResult(@Nullable UserDto value) {
            return change(builder -> builder.addResult = value);
        }

        public UiState withAdding(boolean value) {
            return change(builder -> builder.adding = value);
        }

        public UiState withAddDone(boolean value) {
            return change(builder -> builder.addDone = value);
        }

        public UiState withAddError(@Nullable ApiError value) {
            return change(builder -> builder.addError = value);
        }

        public UiState withDetailUser(@Nullable UserDto value) {
            return change(builder -> builder.detailUser = value);
        }

        public UiState withDetailContact(@Nullable Contact value) {
            return change(builder -> builder.detailContact = value);
        }

        public UiState withAliasInput(String value) {
            return change(builder -> builder.aliasInput = value);
        }

        public UiState withDetailBusy(boolean value) {
            return change(builder -> builder.detailBusy = value);
        }

        public UiState withDetailError(@Nullable ApiError value) {
            return change(builder -> builder.detailError = value);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof UiState that)) {
                return false;
            }
            return searched == that.searched
                    && contactsLoaded == that.contactsLoaded
                    && loading == that.loading
                    && addSearching == that.addSearching
                    && adding == that.adding
                    && addDone == that.addDone
                    && detailBusy == that.detailBusy
                    && query.equals(that.query)
                    && searchResults.equals(that.searchResults)
                    && contacts.equals(that.contacts)
                    && Objects.equals(error, that.error)
                    && addPhone.equals(that.addPhone)
                    && Objects.equals(addResult, that.addResult)
                    && Objects.equals(addError, that.addError)
                    && Objects.equals(detailUser, that.detailUser)
                    && Objects.equals(detailContact, that.detailContact)
                    && aliasInput.equals(that.aliasInput)
                    && Objects.equals(detailError, that.detailError);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    query,
                    searchResults,
                    searched,
                    contacts,
                    contactsLoaded,
                    loading,
                    error,
                    addPhone,
                    addSearching,
                    addResult,
                    adding,
                    addDone,
                    addError,
                    detailUser,
                    detailContact,
                    aliasInput,
                    detailBusy,
                    detailError);
        }

        @Override
        public String toString() {
            return "UiState(query=" + query
                    + ", searchResults=" + searchResults
                    + ", searched=" + searched
                    + ", contacts=" + contacts
                    + ", contactsLoaded=" + contactsLoaded
                    + ", loading=" + loading
                    + ", error=" + error
                    + ", addPhone=" + addPhone
                    + ", addSearching=" + addSearching
                    + ", addResult=" + addResult
                    + ", adding=" + adding
                    + ", addDone=" + addDone
                    + ", addError=" + addError
                    + ", detailUser=" + detailUser
                    + ", detailContact=" + detailContact
                    + ", aliasInput=" + aliasInput
                    + ", detailBusy=" + detailBusy
                    + ", detailError=" + detailError + ")";
        }

        private static final class Builder {
            private String query = "";
            private List<UserDto> searchResults = Collections.emptyList();
            private boolean searched;
            private List<Contact> contacts = Collections.emptyList();
            private boolean contactsLoaded;
            private boolean loading;
            private ApiError error;
            private String addPhone = "";
            private boolean addSearching;
            private UserDto addResult;
            private boolean adding;
            private boolean addDone;
            private ApiError addError;
            private UserDto detailUser;
            private Contact detailContact;
            private String aliasInput = "";
            private boolean detailBusy;
            private ApiError detailError;

            private Builder() {}

            private Builder(UiState source) {
                query = source.query;
                searchResults = source.searchResults;
                searched = source.searched;
                contacts = source.contacts;
                contactsLoaded = source.contactsLoaded;
                loading = source.loading;
                error = source.error;
                addPhone = source.addPhone;
                addSearching = source.addSearching;
                addResult = source.addResult;
                adding = source.adding;
                addDone = source.addDone;
                addError = source.addError;
                detailUser = source.detailUser;
                detailContact = source.detailContact;
                aliasInput = source.aliasInput;
                detailBusy = source.detailBusy;
                detailError = source.detailError;
            }
        }
    }

    public sealed interface Event permits Event.OpenConversation {

        final class OpenConversation implements Event {
            private final String conversationId;

            public OpenConversation(String conversationId) {
                this.conversationId =
                        Objects.requireNonNull(conversationId, "conversationId");
            }

            public String getConversationId() {
                return conversationId;
            }

            @Override
            public boolean equals(Object other) {
                return other instanceof OpenConversation event
                        && conversationId.equals(event.conversationId);
            }

            @Override
            public int hashCode() {
                return conversationId.hashCode();
            }

            @Override
            public String toString() {
                return "OpenConversation(conversationId=" + conversationId + ")";
            }
        }
    }
}
