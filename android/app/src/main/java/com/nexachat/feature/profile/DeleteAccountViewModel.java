package com.nexachat.feature.profile;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.data.AuthRepository;
import com.nexachat.data.ContactsRepository;
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
    private final ContactsRepository contactsRepository;
    private final AccountDeletionCoordinator accountDeletionCoordinator;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<UiState> mutableUiState = new MutableLiveData<>(new UiState());
    private UiState currentState = mutableUiState.getValue();

    @Inject
    public DeleteAccountViewModel(
            @NonNull AuthRepository authRepository,
            @NonNull ContactsRepository contactsRepository,
            @NonNull AccountDeletionCoordinator accountDeletionCoordinator) {
        this.authRepository = Objects.requireNonNull(authRepository, "authRepository");
        this.contactsRepository = Objects.requireNonNull(contactsRepository, "contactsRepository");
        this.accountDeletionCoordinator = Objects.requireNonNull(
                accountDeletionCoordinator, "accountDeletionCoordinator");
        loadProfile();
    }

    DeleteAccountViewModel(
            @NonNull AuthRepository authRepository,
            @NonNull ContactsRepository contactsRepository,
            @NonNull AccountDeletionCoordinator accountDeletionCoordinator,
            boolean skipProfileLoad) {
        this.authRepository = Objects.requireNonNull(authRepository, "authRepository");
        this.contactsRepository = Objects.requireNonNull(contactsRepository, "contactsRepository");
        this.accountDeletionCoordinator = Objects.requireNonNull(
                accountDeletionCoordinator, "accountDeletionCoordinator");
        if (!skipProfileLoad) {
            loadProfile();
        }
    }

    public LiveData<UiState> getUiState() {
        return mutableUiState;
    }

    public void bindProfile(@NonNull UserDto user) {
        updateState(state -> state.withPasswordSet(user.isPasswordSet()).withProfileLoaded(true));
    }

    public void onPasswordChange(String value) {
        Objects.requireNonNull(value, "value");
        updateState(state -> state
                .withPassword(value)
                .withPasswordMissing(false)
                .withError(null));
    }

    public void onCodeChange(String value) {
        Objects.requireNonNull(value, "value");
        String sanitized = RecoveryEmailOtpSanitizer.sanitize(value);
        updateState(state -> state
                .withCode(sanitized)
                .withCodeMissing(false)
                .withCodeInputError(false)
                .withError(null));
    }

    public void sendDeletionCode() {
        UiState state = currentState();
        if (state.isDeleting() || state.isSendingCode() || state.usesPasswordFlow()) {
            return;
        }
        updateState(current -> current.withSendingCode(true).withError(null));
        disposables.add(authRepository.requestAccountDeletionCode()
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> updateState(current -> {
                            if (result instanceof Result.Success<?>) {
                                return current.withSendingCode(false)
                                        .withCodeRequested(true)
                                        .withError(null);
                            }
                            ApiError error = ((Result.Failure<?>) result).getError();
                            return current.withSendingCode(false).withError(error);
                        }),
                        error -> updateState(current -> current
                                .withSendingCode(false)
                                .withError(new ApiError.Network(error.getMessage())))));
    }

    public void submit() {
        UiState state = currentState();
        if (state.isDeleting()) {
            return;
        }
        if (state.usesPasswordFlow()) {
            submitPassword(state);
        } else {
            submitCode(state);
        }
    }

    private void submitPassword(UiState state) {
        String password = state.getPassword();
        if (password.isEmpty()) {
            updateState(current -> current.withPasswordMissing(true));
            return;
        }
        updateState(current -> current.withDeleting(true).withError(null));
        accountDeletionCoordinator.retain(authRepository.deleteAccount(password)
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> updateState(current -> {
                            if (result instanceof Result.Success<?>) {
                                return current.withDeleting(true)
                                        .withPassword("")
                                        .withError(null);
                            }
                            ApiError error = ((Result.Failure<?>) result).getError();
                            return current.withDeleting(false).withError(error);
                        }),
                        error -> updateState(current -> current
                                .withDeleting(false)
                                .withError(new ApiError.Network(error.getMessage())))));
    }

    private void submitCode(UiState state) {
        if (!state.isCodeRequested()) {
            return;
        }
        String code = state.getCode();
        if (code.isEmpty()) {
            updateState(current -> current.withCodeMissing(true));
            return;
        }
        if (!RecoveryEmailOtpSanitizer.isComplete(code)) {
            updateState(current -> current.withCodeMissing(true));
            return;
        }
        updateState(current -> current.withDeleting(true).withError(null).withCodeInputError(false));
        accountDeletionCoordinator.retain(authRepository.deleteAccountWithCode(code)
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> updateState(current -> {
                            if (result instanceof Result.Success<?>) {
                                return current.withDeleting(true)
                                        .withCode("")
                                        .withError(null);
                            }
                            ApiError error = ((Result.Failure<?>) result).getError();
                            boolean codeError = error instanceof ApiError.InvalidOrExpiredCode;
                            return current.withDeleting(false)
                                    .withCodeInputError(codeError)
                                    .withError(error);
                        }),
                        error -> updateState(current -> current
                                .withDeleting(false)
                                .withError(new ApiError.Network(error.getMessage())))));
    }

    private void loadProfile() {
        disposables.add(contactsRepository.getMe()
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> {
                            if (result instanceof Result.Success<?> success) {
                                UserDto user = (UserDto) success.getValue();
                                bindProfile(user);
                                return;
                            }
                            updateState(state -> state.withPasswordSet(true).withProfileLoaded(true));
                        },
                        error -> updateState(state -> state.withPasswordSet(true).withProfileLoaded(true))));
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
        private final boolean passwordSet;
        private final boolean profileLoaded;
        private final String password;
        private final boolean passwordMissing;
        private final boolean codeRequested;
        private final boolean sendingCode;
        private final String code;
        private final boolean codeMissing;
        private final boolean codeInputError;
        private final boolean deleting;
        @Nullable private final ApiError error;

        public UiState() {
            this(true, false, "", false, false, false, "", false, false, false, null);
        }

        private UiState(
                boolean passwordSet,
                boolean profileLoaded,
                String password,
                boolean passwordMissing,
                boolean codeRequested,
                boolean sendingCode,
                String code,
                boolean codeMissing,
                boolean codeInputError,
                boolean deleting,
                @Nullable ApiError error) {
            this.passwordSet = passwordSet;
            this.profileLoaded = profileLoaded;
            this.password = Objects.requireNonNull(password, "password");
            this.passwordMissing = passwordMissing;
            this.codeRequested = codeRequested;
            this.sendingCode = sendingCode;
            this.code = Objects.requireNonNull(code, "code");
            this.codeMissing = codeMissing;
            this.codeInputError = codeInputError;
            this.deleting = deleting;
            this.error = error;
        }

        public boolean usesPasswordFlow() {
            return passwordSet;
        }

        public boolean isPasswordSet() {
            return passwordSet;
        }

        public boolean isProfileLoaded() {
            return profileLoaded;
        }

        public String getPassword() {
            return password;
        }

        public boolean isPasswordMissing() {
            return passwordMissing;
        }

        public boolean isCodeRequested() {
            return codeRequested;
        }

        public boolean isSendingCode() {
            return sendingCode;
        }

        public String getCode() {
            return code;
        }

        public boolean isCodeMissing() {
            return codeMissing;
        }

        public boolean isCodeInputError() {
            return codeInputError;
        }

        public boolean isDeleting() {
            return deleting;
        }

        @Nullable
        public ApiError getError() {
            return error;
        }

        UiState withPasswordSet(boolean value) {
            return new UiState(
                    value, profileLoaded, password, passwordMissing, codeRequested, sendingCode,
                    code, codeMissing, codeInputError, deleting, error);
        }

        UiState withProfileLoaded(boolean value) {
            return new UiState(
                    passwordSet, value, password, passwordMissing, codeRequested, sendingCode,
                    code, codeMissing, codeInputError, deleting, error);
        }

        UiState withPassword(String value) {
            return new UiState(
                    passwordSet, profileLoaded, value, passwordMissing, codeRequested, sendingCode,
                    code, codeMissing, codeInputError, deleting, error);
        }

        UiState withPasswordMissing(boolean value) {
            return new UiState(
                    passwordSet, profileLoaded, password, value, codeRequested, sendingCode,
                    code, codeMissing, codeInputError, deleting, error);
        }

        UiState withCodeRequested(boolean value) {
            return new UiState(
                    passwordSet, profileLoaded, password, passwordMissing, value, sendingCode,
                    code, codeMissing, codeInputError, deleting, error);
        }

        UiState withSendingCode(boolean value) {
            return new UiState(
                    passwordSet, profileLoaded, password, passwordMissing, codeRequested, value,
                    code, codeMissing, codeInputError, deleting, error);
        }

        UiState withCode(String value) {
            return new UiState(
                    passwordSet, profileLoaded, password, passwordMissing, codeRequested, sendingCode,
                    value, codeMissing, codeInputError, deleting, error);
        }

        UiState withCodeMissing(boolean value) {
            return new UiState(
                    passwordSet, profileLoaded, password, passwordMissing, codeRequested, sendingCode,
                    code, value, codeInputError, deleting, error);
        }

        UiState withCodeInputError(boolean value) {
            return new UiState(
                    passwordSet, profileLoaded, password, passwordMissing, codeRequested, sendingCode,
                    code, codeMissing, value, deleting, error);
        }

        UiState withDeleting(boolean value) {
            return new UiState(
                    passwordSet, profileLoaded, password, passwordMissing, codeRequested, sendingCode,
                    code, codeMissing, codeInputError, value, error);
        }

        UiState withError(@Nullable ApiError value) {
            return new UiState(
                    passwordSet, profileLoaded, password, passwordMissing, codeRequested, sendingCode,
                    code, codeMissing, codeInputError, deleting, value);
        }
    }
}
