package com.nexachat.feature.auth;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.data.ContactsRepository;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Objects;
import java.util.function.UnaryOperator;
import javax.inject.Inject;

/** One-shot profile completion after Google account creation. */
@HiltViewModel
public final class CompleteProfileViewModel extends ViewModel {

    private final ContactsRepository contactsRepository;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<UiState> mutableUiState = new MutableLiveData<>(new UiState());
    private final OneShotLiveData<Event> events = new OneShotLiveData<>();
    private UiState currentState = mutableUiState.getValue();

    @Inject
    public CompleteProfileViewModel(ContactsRepository contactsRepository) {
        this.contactsRepository = Objects.requireNonNull(contactsRepository, "contactsRepository");
        load();
    }

    public LiveData<UiState> getUiState() {
        return mutableUiState;
    }

    public LiveData<Event> getEvents() {
        return events;
    }

    public void load() {
        updateState(state -> state.withLoading(true).withError(null));
        disposables.add(contactsRepository.getMe()
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> {
                            if (result instanceof Result.Success<?> success) {
                                UserDto user = (UserDto) success.getValue();
                                String name = user.getDisplayName() == null ? "" : user.getDisplayName();
                                updateState(state -> state
                                        .withLoading(false)
                                        .withUser(user)
                                        .withDisplayName(name)
                                        .withNameError(false));
                            } else {
                                ApiError error = ((Result.Failure<?>) result).getError();
                                updateState(state -> state.withLoading(false).withError(error));
                            }
                        },
                        error -> updateState(state -> state.withLoading(false).withError(
                                new ApiError.Network(error.getMessage())))));
    }

    public void onDisplayNameChange(@NonNull String value) {
        updateState(state -> state.withDisplayName(value).withNameError(false));
    }

    public void continueToInbox() {
        UiState state = currentState();
        if (state.getLoading() || state.getSaving()) {
            return;
        }
        String trimmed = CompleteProfileDisplayNameRules.normalize(state.getDisplayName());
        if (!CompleteProfileDisplayNameRules.isValid(trimmed)) {
            updateState(current -> current.withNameError(true));
            return;
        }
        UserDto user = state.getUser();
        String initial = user == null || user.getDisplayName() == null
                ? "" : CompleteProfileDisplayNameRules.normalize(user.getDisplayName());
        if (trimmed.equals(initial)) {
            events.postValue(Event.Continue);
            return;
        }
        updateState(current -> current.withSaving(true).withError(null));
        disposables.add(contactsRepository.updateDisplayName(trimmed)
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> {
                            if (result instanceof Result.Success<?> success) {
                                updateState(current -> current
                                        .withSaving(false)
                                        .withUser((UserDto) success.getValue()));
                                events.postValue(Event.Continue);
                            } else {
                                ApiError error = ((Result.Failure<?>) result).getError();
                                updateState(current -> current.withSaving(false).withError(error));
                            }
                        },
                        error -> updateState(current -> current.withSaving(false).withError(
                                new ApiError.Network(error.getMessage())))));
    }

    private synchronized UiState currentState() {
        return currentState;
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

    public enum Event {
        Continue
    }

    public static final class UiState {
        @Nullable private final UserDto user;
        @NonNull private final String displayName;
        private final boolean loading;
        private final boolean saving;
        private final boolean nameError;
        @Nullable private final ApiError error;

        public UiState() {
            this(null, "", false, false, false, null);
        }

        public UiState(
                @Nullable UserDto user,
                @NonNull String displayName,
                boolean loading,
                boolean saving,
                boolean nameError,
                @Nullable ApiError error) {
            this.user = user;
            this.displayName = displayName;
            this.loading = loading;
            this.saving = saving;
            this.nameError = nameError;
            this.error = error;
        }

        @Nullable
        public UserDto getUser() {
            return user;
        }

        @NonNull
        public String getDisplayName() {
            return displayName;
        }

        public boolean getLoading() {
            return loading;
        }

        public boolean getSaving() {
            return saving;
        }

        public boolean getNameError() {
            return nameError;
        }

        @Nullable
        public ApiError getError() {
            return error;
        }

        public UiState withUser(@Nullable UserDto value) {
            return new UiState(value, displayName, loading, saving, nameError, error);
        }

        public UiState withDisplayName(@NonNull String value) {
            return new UiState(user, value, loading, saving, nameError, error);
        }

        public UiState withLoading(boolean value) {
            return new UiState(user, displayName, value, saving, nameError, error);
        }

        public UiState withSaving(boolean value) {
            return new UiState(user, displayName, loading, value, nameError, error);
        }

        public UiState withNameError(boolean value) {
            return new UiState(user, displayName, loading, saving, value, error);
        }

        public UiState withError(@Nullable ApiError value) {
            return new UiState(user, displayName, loading, saving, nameError, value);
        }
    }
}
