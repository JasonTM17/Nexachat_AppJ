package com.nexachat.feature.contacts;

import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.annotation.Nullable;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.data.Contact;
import com.nexachat.data.ContactsRepository;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import javax.inject.Inject;

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
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<UiState> mutableUiState;
    private final OneShotLiveData<Event> events = new OneShotLiveData<>();
    private UiState currentState;
    private List<Contact> currentContacts = Collections.emptyList();

    @Inject
    public ContactsViewModel(
            SavedStateHandle savedStateHandle,
            ContactsRepository contactsRepository) {
        this.savedStateHandle =
                Objects.requireNonNull(savedStateHandle, "savedStateHandle");
        this.contactsRepository =
                Objects.requireNonNull(contactsRepository, "contactsRepository");
        String restoredQuery = savedStateHandle.get(KEY_SEARCH_QUERY);
        UiState initialState = new UiState().withQuery(restoredQuery == null ? "" : restoredQuery);
        mutableUiState = new MutableLiveData<>(initialState);
        currentState = initialState;
        disposables.add(contactsRepository.getContactsFlow()
                .subscribeOn(Schedulers.io())
                .subscribe(
                        contactsOpt -> {
                            if (contactsOpt.isEmpty()) {
                                return;
                            }
                            List<Contact> snapshot = List.copyOf(contactsOpt.get());
                            synchronized (this) {
                                currentContacts = snapshot;
                            }
                            updateState(state -> state.withContacts(snapshot));
                        },
                        error -> updateState(state -> state.withError(
                                new ApiError.Network(error.getMessage())))));
        loadContacts();
    }

    public LiveData<UiState> getUiState() {
        return mutableUiState;
    }

    public LiveData<Event> getEvents() {
        return events;
    }

    /** Loads the shared contact list once for this ViewModel instance. */
    public void loadContacts() {
        updateState(state -> state.withLoading(true).withError(null));
        disposables.add(contactsRepository.contacts()
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> updateState(state -> result instanceof Result.Success<?>
                                ? state.withLoading(false).withContactsLoaded(true)
                                : state.withLoading(false)
                                        .withError(((Result.Failure<?>) result).getError())
                                        .withContactsLoaded(true)),
                        error -> updateState(state -> state.withLoading(false)
                                .withError(new ApiError.Network(error.getMessage()))
                                .withContactsLoaded(true))));
    }

    public void onQueryChange(String value) {
        Objects.requireNonNull(value, "value");
        savedStateHandle.set(KEY_SEARCH_QUERY, value);
        updateState(state -> state
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
        UiState state = currentState();
        String query = trimWhitespace(state.getQuery());
        if (query.isEmpty() || state.isLoading()) {
            return;
        }
        updateState(current -> current.withLoading(true).withError(null).withSearched(true));
        Single<Result<List<UserDto>>> request;
        if (isPhoneQuery(query)) {
            request = contactsRepository.searchByPhone(query);
        } else if (AddContactInputDetector.containsEmailHint(query)) {
            request = contactsRepository.searchByEmail(
                    AddContactInputDetector.normalizeEmail(query));
        } else {
            request = contactsRepository.searchByName(query);
        }
        disposables.add(request.subscribeOn(Schedulers.io())
                .subscribe(
                        result -> updateState(current -> result instanceof Result.Success<?> success
                                ? current.withLoading(false)
                                        .withSearchResults((List<UserDto>) success.getValue())
                                : current.withLoading(false)
                                        .withError(((Result.Failure<?>) result).getError())
                                        .withSearchResults(Collections.emptyList())),
                        error -> updateState(current -> current.withLoading(false)
                                .withError(new ApiError.Network(error.getMessage()))
                                .withSearchResults(Collections.emptyList()))));
    }

    public void onAddPhoneChange(String value) {
        Objects.requireNonNull(value, "value");
        updateState(state -> state
                .withAddPhone(value)
                .withAddError(null)
                .withAddResult(null)
                .withAddDone(false));
    }

    /** Finds a user for confirmation before adding by phone or email. */
    public void findUserForAdd() {
        UiState state = currentState();
        String raw = trimWhitespace(state.getAddPhone());
        if (raw.isEmpty() || state.isAddSearching()) {
            return;
        }
        AddContactInputDetector.Kind kind = AddContactInputDetector.classify(raw);
        if (kind == AddContactInputDetector.Kind.INVALID) {
            updateState(current -> current.withAddError(
                    AddContactInputDetector.containsEmailHint(raw)
                            ? ApiError.RecoveryEmailInvalid.INSTANCE
                            : new ApiError.Unknown("USER_NOT_FOUND")));
            return;
        }
        updateState(current -> current.withAddSearching(true).withAddError(null).withAddResult(null));
        Single<Result<List<UserDto>>> lookup = kind == AddContactInputDetector.Kind.EMAIL
                ? contactsRepository.searchByEmail(AddContactInputDetector.normalizeEmail(raw))
                : contactsRepository.searchByPhone(raw);
        disposables.add(lookup
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> updateState(current -> {
                            if (result instanceof Result.Success<?> success) {
                                List<UserDto> matches = (List<UserDto>) success.getValue();
                                UserDto match = matches.isEmpty() ? null : matches.get(0);
                                return current.withAddSearching(false)
                                        .withAddResult(match)
                                        .withAddError(match == null
                                                ? new ApiError.Unknown("USER_NOT_FOUND")
                                                : null);
                            }
                            return current.withAddSearching(false)
                                    .withAddError(((Result.Failure<?>) result).getError());
                        }),
                        error -> updateState(current -> current.withAddSearching(false)
                                .withAddError(new ApiError.Network(error.getMessage())))));
    }

    /** Adds the confirmed user, or retries from the entered phone or email. */
    public void confirmAdd() {
        UiState state = currentState();
        if (state.isAdding()) {
            return;
        }
        if (state.getAddResult() != null) {
            updateState(current -> current.withAdding(true).withAddError(null));
            submitAddForUser(state.getAddResult(), false);
            return;
        }
        String raw = trimWhitespace(state.getAddPhone());
        if (raw.isEmpty()) {
            return;
        }
        AddContactInputDetector.Kind kind = AddContactInputDetector.classify(raw);
        if (kind == AddContactInputDetector.Kind.INVALID) {
            updateState(current -> current.withAddError(
                    AddContactInputDetector.containsEmailHint(raw)
                            ? ApiError.RecoveryEmailInvalid.INSTANCE
                            : new ApiError.Unknown("USER_NOT_FOUND")));
            return;
        }
        updateState(current -> current.withAdding(true).withAddError(null));
        if (kind == AddContactInputDetector.Kind.EMAIL) {
            submitAddContactByEmail(AddContactInputDetector.normalizeEmail(raw), false);
        } else {
            submitAddContact(raw, false);
        }
    }

    /** Resets the add-by-phone flow when its sheet is dismissed. */
    public void resetAddFlow() {
        updateState(state -> state
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
        for (Contact contact : currentContacts()) {
            if (contact.getUserId().equals(user.getId())) {
                known = contact;
                break;
            }
        }
        Contact selected = known;
        updateState(state -> state
                .withDetailUser(user)
                .withDetailContact(selected)
                .withAliasInput(selected == null || selected.getAliasName() == null
                        ? ""
                        : selected.getAliasName())
                .withDetailError(null));
    }

    public void openContactDetail(Contact contact) {
        Objects.requireNonNull(contact, "contact");
        UserDto user = new UserDto(
                contact.getUserId(),
                contact.getPhone(),
                contact.getDisplayName(),
                null,
                contact.getEmail(),
                contact.getEmailVerified());
        updateState(state -> state
                .withDetailUser(user)
                .withDetailContact(contact)
                .withAliasInput(contact.getAliasName() == null ? "" : contact.getAliasName())
                .withDetailError(null));
    }

    public void closeDetail() {
        updateState(state -> state
                .withDetailUser(null)
                .withDetailContact(null)
                .withAliasInput("")
                .withDetailBusy(false)
                .withDetailError(null));
    }

    public void onAliasChange(String value) {
        Objects.requireNonNull(value, "value");
        updateState(state -> state
                .withAliasInput(value)
                .withDetailError(null));
    }

    /** Persists the selected contact's alias; blank input clears it. */
    public void saveAlias() {
        UiState state = currentState();
        Contact contact = state.getDetailContact();
        if (contact == null || state.isDetailBusy()) {
            return;
        }
        String alias = trimWhitespace(state.getAliasInput());
        if (!hasNonWhitespace(alias)) {
            alias = null;
        }
        String requestedAlias = alias;
        updateState(current -> current.withDetailBusy(true).withDetailError(null));
        disposables.add(contactsRepository.setAlias(contact.getUserId(), requestedAlias)
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> updateState(current -> result instanceof Result.Success<?> success
                                ? current.withDetailBusy(false)
                                        .withDetailContact((Contact) success.getValue())
                                : current.withDetailBusy(false).withDetailError(
                                        ((Result.Failure<?>) result).getError())),
                        error -> updateState(current -> current.withDetailBusy(false)
                                .withDetailError(new ApiError.Network(error.getMessage())))));
    }

    public void removeSelectedContact() {
        UiState state = currentState();
        Contact contact = state.getDetailContact();
        if (contact == null || state.isDetailBusy()) {
            return;
        }
        updateState(current -> current.withDetailBusy(true).withDetailError(null));
        disposables.add(contactsRepository.removeContact(contact.getUserId())
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> updateState(current -> result instanceof Result.Success<?>
                                ? current.withDetailBusy(false)
                                        .withDetailUser(null).withDetailContact(null)
                                : current.withDetailBusy(false).withDetailError(
                                        ((Result.Failure<?>) result).getError())),
                        error -> updateState(current -> current.withDetailBusy(false)
                                .withDetailError(new ApiError.Network(error.getMessage())))));
    }

    /** Adds the selected search result as a contact. */
    public void addDetailContact() {
        UiState state = currentState();
        UserDto user = state.getDetailUser();
        if (user == null || state.isDetailBusy()) {
            return;
        }
        updateState(current -> current.withDetailBusy(true).withDetailError(null));
        submitAddForUser(user, true);
    }

    /** Opens the selected user's direct conversation and emits navigation. */
    public void messageSelected() {
        UiState state = currentState();
        UserDto user = state.getDetailUser();
        if (user == null || state.isDetailBusy()) {
            return;
        }
        updateState(current -> current.withDetailBusy(true).withDetailError(null));
        disposables.add(contactsRepository.openDirectConversation(user.getId(), user.getPhone())
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> {
                            if (result instanceof Result.Success<?> success) {
                                String conversationId = (String) success.getValue();
                                updateState(current -> current.withDetailBusy(false));
                                events.postValue(new Event.OpenConversation(conversationId));
                            } else {
                                updateState(current -> current.withDetailBusy(false)
                                        .withDetailError(
                                                ((Result.Failure<?>) result).getError()));
                            }
                        },
                        error -> updateState(current -> current.withDetailBusy(false)
                                .withDetailError(new ApiError.Network(error.getMessage())))));
    }

    private void submitAddForUser(UserDto user, boolean detail) {
        if (user.getPhone() != null && !user.getPhone().isBlank()) {
            submitAddContact(user.getPhone(), detail);
            return;
        }
        if (user.hasVerifiedRecoveryEmail()) {
            submitAddContactByEmail(user.getEmail(), detail);
            return;
        }
        updateState(current -> detail
                ? current.withDetailBusy(false).withDetailError(new ApiError.Unknown("USER_NOT_FOUND"))
                : current.withAdding(false).withAddError(new ApiError.Unknown("USER_NOT_FOUND")));
    }

    private void submitAddContactByEmail(String email, boolean detail) {
        disposables.add(contactsRepository.getMe()
                .subscribeOn(Schedulers.io())
                .flatMap(meResult -> {
                    if (meResult instanceof Result.Success<?> success) {
                        UserDto me = (UserDto) success.getValue();
                        if (SelfContactGuard.isOwnEmail(email, me.getEmail())) {
                            return Single.just(Result.<Contact>failure(
                                    new ApiError.Unknown(SelfContactGuard.ERROR_CODE)));
                        }
                    }
                    return contactsRepository.addContactByEmail(email);
                })
                .subscribe(
                        result -> updateState(current -> {
                            if (detail) {
                                return result instanceof Result.Success<?> success
                                        ? current.withDetailBusy(false)
                                                .withDetailContact((Contact) success.getValue())
                                        : current.withDetailBusy(false).withDetailError(
                                                ((Result.Failure<?>) result).getError());
                            }
                            return result instanceof Result.Success<?>
                                    ? current.withAdding(false).withAddDone(true)
                                    : current.withAdding(false).withAddError(
                                            ((Result.Failure<?>) result).getError());
                        }),
                        error -> updateState(current -> detail
                                ? current.withDetailBusy(false).withDetailError(
                                        new ApiError.Network(error.getMessage()))
                                : current.withAdding(false).withAddError(
                                        new ApiError.Network(error.getMessage())))));
    }

    private void submitAddContact(String phone, boolean detail) {
        disposables.add(contactsRepository.getMe()
                .subscribeOn(Schedulers.io())
                .flatMap(meResult -> {
                    if (meResult instanceof Result.Success<?> success) {
                        UserDto me = (UserDto) success.getValue();
                        if (SelfContactGuard.isOwnPhone(phone, me.getPhone())) {
                            return Single.just(Result.<Contact>failure(
                                    new ApiError.Unknown(SelfContactGuard.ERROR_CODE)));
                        }
                    }
                    return contactsRepository.addContact(phone);
                })
                .subscribe(
                        result -> updateState(current -> {
                            if (detail) {
                                return result instanceof Result.Success<?> success
                                        ? current.withDetailBusy(false)
                                                .withDetailContact((Contact) success.getValue())
                                        : current.withDetailBusy(false).withDetailError(
                                                ((Result.Failure<?>) result).getError());
                            }
                            return result instanceof Result.Success<?>
                                    ? current.withAdding(false).withAddDone(true)
                                    : current.withAdding(false).withAddError(
                                            ((Result.Failure<?>) result).getError());
                        }),
                        error -> updateState(current -> detail
                                ? current.withDetailBusy(false).withDetailError(
                                        new ApiError.Network(error.getMessage()))
                                : current.withAdding(false).withAddError(
                                        new ApiError.Network(error.getMessage())))));
    }

    private synchronized UiState currentState() {
        return currentState;
    }

    private synchronized List<Contact> currentContacts() {
        return currentContacts;
    }

    private synchronized void updateState(UnaryOperator<UiState> reducer) {
        currentState = Objects.requireNonNull(reducer.apply(currentState), "state");
        mutableUiState.postValue(currentState);
    }

    @Override
    protected void onCleared() {
        disposables.clear();
        super.onCleared();
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

/** LiveData event stream that does not redeliver a consumed value after rotation. */
final class OneShotLiveData<T> extends MutableLiveData<T> {

    private final AtomicBoolean pending = new AtomicBoolean(false);
    private final java.util.Map<androidx.lifecycle.Observer<? super T>,
            androidx.lifecycle.Observer<T>> foreverObservers = new java.util.IdentityHashMap<>();

    @Override
    public void observe(
            @androidx.annotation.NonNull androidx.lifecycle.LifecycleOwner owner,
            @androidx.annotation.NonNull androidx.lifecycle.Observer<? super T> observer) {
        super.observe(owner, gated(observer));
    }

    @Override
    public void observeForever(
            @androidx.annotation.NonNull androidx.lifecycle.Observer<? super T> observer) {
        androidx.lifecycle.Observer<T> wrapper = gated(observer);
        synchronized (foreverObservers) {
            foreverObservers.put(observer, wrapper);
        }
        super.observeForever(wrapper);
    }

    @Override
    public void removeObserver(
            @androidx.annotation.NonNull androidx.lifecycle.Observer<? super T> observer) {
        androidx.lifecycle.Observer<T> wrapper;
        synchronized (foreverObservers) {
            wrapper = foreverObservers.remove(observer);
        }
        super.removeObserver(wrapper == null ? observer : wrapper);
    }

    @Override
    public void postValue(@Nullable T value) {
        pending.set(true);
        super.postValue(value);
    }

    @Override
    public void setValue(@Nullable T value) {
        pending.set(true);
        super.setValue(value);
    }

    private androidx.lifecycle.Observer<T> gated(
            androidx.lifecycle.Observer<? super T> observer) {
        return value -> {
            if (pending.compareAndSet(true, false)) {
                observer.onChanged(value);
            }
        };
    }
}
