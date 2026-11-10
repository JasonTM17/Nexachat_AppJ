package com.nexachat.feature.profile;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.common.Unit;
import com.nexachat.data.RecoveryEmailRepository;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Locale;
import java.util.Objects;
import java.util.function.UnaryOperator;
import javax.inject.Inject;

@HiltViewModel
public final class RecoveryEmailFormViewModel extends ViewModel {

    private static final String KEY_EMAIL_INPUT = "recovery_form_email";

    private final RecoveryEmailRepository repository;
    private final SavedStateHandle savedState;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<UiState> mutableUiState = new MutableLiveData<>(new UiState());
    private UiState currentState = mutableUiState.getValue();

    @Inject
    public RecoveryEmailFormViewModel(
            RecoveryEmailRepository repository,
            SavedStateHandle savedState) {
        this.repository = Objects.requireNonNull(repository);
        this.savedState = Objects.requireNonNull(savedState);
        String restored = savedState.get(KEY_EMAIL_INPUT);
        if (restored != null) {
            updateState(state -> state.withEmailInput(restored));
        }
    }

    public LiveData<UiState> getUiState() {
        return mutableUiState;
    }

    public void onEmailChanged(@Nullable String value) {
        String next = value == null ? "" : value;
        savedState.set(KEY_EMAIL_INPUT, next);
        updateState(state -> state
                .withEmailInput(next)
                .withEmailInvalid(false)
                .withError(null)
                .withCodeSent(false));
    }

    public void sendCode() {
        UiState state = currentState();
        if (state.isSending()) {
            return;
        }
        String email = state.getEmailInput().trim();
        if (!RecoveryEmailAddressValidator.isValid(email)) {
            updateState(current -> current.withEmailInvalid(true));
            return;
        }
        updateState(current -> current
                .withSending(true)
                .withError(null)
                .withCodeSent(false)
                .withEmailInvalid(false));
        disposables.add(repository.requestVerificationCode(email)
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> updateState(current -> {
                            if (result instanceof Result.Success<?>) {
                                return current.withSending(false)
                                        .withPendingEmail(email.trim().toLowerCase(Locale.ROOT))
                                        .withCodeSent(true);
                            }
                            return current.withSending(false).withError(
                                    ((Result.Failure<Unit>) result).getError());
                        }),
                        error -> updateState(current -> current.withSending(false).withError(
                                new ApiError.Network(error.getMessage())))));
    }

    public void consumeCodeSent() {
        updateState(state -> state.withCodeSent(false));
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
        private final String emailInput;
        @Nullable private final String pendingEmail;
        private final boolean emailInvalid;
        private final boolean sending;
        @Nullable private final ApiError error;
        private final boolean codeSent;

        public UiState() {
            emailInput = "";
            pendingEmail = null;
            emailInvalid = false;
            sending = false;
            error = null;
            codeSent = false;
        }

        private UiState(
                String emailInput,
                @Nullable String pendingEmail,
                boolean emailInvalid,
                boolean sending,
                @Nullable ApiError error,
                boolean codeSent) {
            this.emailInput = emailInput;
            this.pendingEmail = pendingEmail;
            this.emailInvalid = emailInvalid;
            this.sending = sending;
            this.error = error;
            this.codeSent = codeSent;
        }

        public String getEmailInput() {
            return emailInput;
        }

        @Nullable
        public String getPendingEmail() {
            return pendingEmail;
        }

        public boolean isEmailInvalid() {
            return emailInvalid;
        }

        public boolean isSending() {
            return sending;
        }

        @Nullable
        public ApiError getError() {
            return error;
        }

        public boolean isCodeSent() {
            return codeSent;
        }

        public UiState withEmailInput(String value) {
            return new UiState(value, pendingEmail, emailInvalid, sending, error, codeSent);
        }

        public UiState withPendingEmail(@Nullable String value) {
            return new UiState(emailInput, value, emailInvalid, sending, error, codeSent);
        }

        public UiState withEmailInvalid(boolean value) {
            return new UiState(emailInput, pendingEmail, value, sending, error, codeSent);
        }

        public UiState withSending(boolean value) {
            return new UiState(emailInput, pendingEmail, emailInvalid, value, error, codeSent);
        }

        public UiState withError(@Nullable ApiError value) {
            return new UiState(emailInput, pendingEmail, emailInvalid, sending, value, codeSent);
        }

        public UiState withCodeSent(boolean value) {
            return new UiState(emailInput, pendingEmail, emailInvalid, sending, error, value);
        }
    }
}
