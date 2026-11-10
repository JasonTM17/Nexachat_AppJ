package com.nexachat.feature.profile;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.data.AuthRepository;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Objects;
import java.util.function.UnaryOperator;
import javax.inject.Inject;

/** Confirmation and DELETE /users/me for permanent account removal. */
@HiltViewModel
public final class DeleteAccountViewModel extends ViewModel {

    private final AuthRepository authRepository;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<UiState> mutableUiState = new MutableLiveData<>(new UiState());
    private UiState currentState = mutableUiState.getValue();

    @Inject
    public DeleteAccountViewModel(@NonNull AuthRepository authRepository) {
        this.authRepository = Objects.requireNonNull(authRepository, "authRepository");
    }

    public LiveData<UiState> getUiState() {
        return mutableUiState;
    }

    public void onPasswordChange(String value) {
        Objects.requireNonNull(value, "value");
        updateState(state -> state
                .withPassword(value)
                .withPasswordMissing(false)
                .withError(null));
    }

    public void submit() {
        UiState state = currentState();
        if (state.isDeleting()) {
            return;
        }
        String password = state.getPassword();
        if (password.isEmpty()) {
            updateState(current -> current.withPasswordMissing(true));
            return;
        }
        updateState(current -> current.withDeleting(true).withError(null));
        disposables.add(authRepository.deleteAccount(password)
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> updateState(current -> {
                            if (result instanceof Result.Success<?>) {
                                return current.withDeleting(true).withError(null);
                            }
                            ApiError error = ((Result.Failure<?>) result).getError();
                            return current.withDeleting(false).withError(error);
                        }),
                        error -> updateState(current -> current
                                .withDeleting(false)
                                .withError(new ApiError.Network(error.getMessage())))));
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
        private final String password;
        private final boolean passwordMissing;
        private final boolean deleting;
        @Nullable private final ApiError error;

        public UiState() {
            this("", false, false, null);
        }

        private UiState(
                String password,
                boolean passwordMissing,
                boolean deleting,
                @Nullable ApiError error) {
            this.password = Objects.requireNonNull(password, "password");
            this.passwordMissing = passwordMissing;
            this.deleting = deleting;
            this.error = error;
        }

        public String getPassword() {
            return password;
        }

        public boolean isPasswordMissing() {
            return passwordMissing;
        }

        public boolean isDeleting() {
            return deleting;
        }

        @Nullable
        public ApiError getError() {
            return error;
        }

        UiState withPassword(String value) {
            return new UiState(value, passwordMissing, deleting, error);
        }

        UiState withPasswordMissing(boolean value) {
            return new UiState(password, value, deleting, error);
        }

        UiState withDeleting(boolean value) {
            return new UiState(password, passwordMissing, value, error);
        }

        UiState withError(@Nullable ApiError value) {
            return new UiState(password, passwordMissing, deleting, value);
        }
    }
}
