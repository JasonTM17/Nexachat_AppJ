package com.nexachat.feature.profile;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.data.ContactsRepository;
import com.nexachat.data.RecoveryEmailRepository;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Objects;
import java.util.function.UnaryOperator;
import javax.inject.Inject;

@HiltViewModel
public final class RecoveryEmailManageViewModel extends ViewModel {

    public static final String RESULT_PROFILE_RELOAD = "recovery_email_profile_reload";

    private final ContactsRepository contactsRepository;
    private final RecoveryEmailRepository recoveryEmailRepository;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<UiState> mutableUiState = new MutableLiveData<>(new UiState());
    private UiState currentState = mutableUiState.getValue();

    @Inject
    public RecoveryEmailManageViewModel(
            ContactsRepository contactsRepository,
            RecoveryEmailRepository recoveryEmailRepository) {
        this.contactsRepository = Objects.requireNonNull(contactsRepository);
        this.recoveryEmailRepository = Objects.requireNonNull(recoveryEmailRepository);
        load();
    }

    public LiveData<UiState> getUiState() {
        return mutableUiState;
    }

    public void load() {
        updateState(state -> state.withLoading(true).withError(null));
        disposables.add(contactsRepository.getMe()
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> updateState(state -> result instanceof Result.Success<?> success
                                ? state.withLoading(false)
                                        .withUser((UserDto) success.getValue())
                                        .withRemoveComplete(false)
                                : state.withLoading(false).withError(
                                        ((Result.Failure<?>) result).getError())),
                        error -> updateState(state -> state.withLoading(false).withError(
                                new ApiError.Network(error.getMessage())))));
    }

    public void removeEmail() {
        if (currentState().isRemoving()) {
            return;
        }
        updateState(state -> state.withRemoving(true).withError(null));
        disposables.add(recoveryEmailRepository.removeVerifiedEmail()
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> updateState(state -> {
                            if (result instanceof Result.Success<?>) {
                                return state.withRemoving(false)
                                        .withRemoveComplete(true)
                                        .withUser(clearedEmail(state.getUser()));
                            }
                            return state.withRemoving(false).withError(
                                    ((Result.Failure<?>) result).getError());
                        }),
                        error -> updateState(state -> state.withRemoving(false).withError(
                                new ApiError.Network(error.getMessage())))));
    }

    public void consumeRemoveComplete() {
        updateState(state -> state.withRemoveComplete(false));
    }

    @Nullable
    private static UserDto clearedEmail(@Nullable UserDto user) {
        if (user == null) {
            return null;
        }
        return user.withRecoveryEmail(null, false);
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

    public static final class UiState {
        private final boolean loading;
        @Nullable private final UserDto user;
        @Nullable private final ApiError error;
        private final boolean removing;
        private final boolean removeComplete;

        public UiState() {
            loading = true;
            user = null;
            error = null;
            removing = false;
            removeComplete = false;
        }

        private UiState(
                boolean loading,
                @Nullable UserDto user,
                @Nullable ApiError error,
                boolean removing,
                boolean removeComplete) {
            this.loading = loading;
            this.user = user;
            this.error = error;
            this.removing = removing;
            this.removeComplete = removeComplete;
        }

        public boolean isLoading() {
            return loading;
        }

        @Nullable
        public UserDto getUser() {
            return user;
        }

        @Nullable
        public ApiError getError() {
            return error;
        }

        public boolean isRemoving() {
            return removing;
        }

        public boolean isRemoveComplete() {
            return removeComplete;
        }

        public UiState withLoading(boolean value) {
            return new UiState(value, user, error, removing, removeComplete);
        }

        public UiState withUser(@Nullable UserDto value) {
            return new UiState(loading, value, error, removing, removeComplete);
        }

        public UiState withError(@Nullable ApiError value) {
            return new UiState(loading, user, value, removing, removeComplete);
        }

        public UiState withRemoving(boolean value) {
            return new UiState(loading, user, error, value, removeComplete);
        }

        public UiState withRemoveComplete(boolean value) {
            return new UiState(loading, user, error, removing, value);
        }
    }
}
