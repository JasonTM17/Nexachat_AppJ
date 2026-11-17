package com.nexachat.feature.auth;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import com.nexachat.core.auth.GoogleSignInFlow;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.common.Unit;
import com.nexachat.core.network.dto.AuthResponse;
import com.nexachat.data.AuthRepository;
import com.nexachat.data.AuthSessionResult;
import dagger.hilt.android.lifecycle.HiltViewModel;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import javax.inject.Inject;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Shared ViewModel for the Login and Register destinations (each nav entry
 * gets its own instance via {@code hiltViewModel()} — no state leaks between them).
 *
 * <p>Field inputs live in {@link UiState} so they survive config changes;
 * one-shot navigation is consumed by a single observer cycle.
 */
@HiltViewModel
public final class AuthViewModel extends ViewModel {

    public static final int MIN_PASSWORD_LENGTH = 8;
    static final String KEY_PENDING_COMPLETE_PROFILE = "pendingCompleteProfile";

    private final AuthRepository authRepository;
    private final GoogleSignInFlow googleSignInFlow;
    private final SavedStateHandle savedState;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<UiState> mutableUiState = new MutableLiveData<>(new UiState());
    private final MutableLiveData<Boolean> serverStarting = new MutableLiveData<>(false);
    private final OneShotLiveData<Event> events = new OneShotLiveData<>();
    private UiState currentState = mutableUiState.getValue();
    private final MutableLiveData<EmailSignupState> mutableEmailSignup =
            new MutableLiveData<>(new EmailSignupState());
    private EmailSignupState emailSignupState = mutableEmailSignup.getValue();
    private final Clock clock;

    @Inject
    public AuthViewModel(
            AuthRepository authRepository,
            GoogleSignInFlow googleSignInFlow,
            SavedStateHandle savedState) {
        this(authRepository, googleSignInFlow, savedState, Clock.systemUTC());
    }

    AuthViewModel(
            AuthRepository authRepository,
            GoogleSignInFlow googleSignInFlow,
            SavedStateHandle savedState,
            Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.authRepository = Objects.requireNonNull(authRepository, "authRepository");
        this.googleSignInFlow = Objects.requireNonNull(googleSignInFlow, "googleSignInFlow");
        this.savedState = Objects.requireNonNull(savedState, "savedState");
        Flowable<Boolean> wakeState = authRepository.getServerWakeState();
        if (wakeState != null) {
            disposables.add(wakeState.subscribe(
                    serverStarting::postValue,
                    ignored -> serverStarting.postValue(false)));
        }
    }

    /** True when the build carries a Google web client ID; otherwise the button stays hidden. */
    public boolean isGoogleSignInAvailable() {
        return googleSignInFlow.isConfigured();
    }

    /**
     * Fetches the sign-in nonce while the screen is idle so the tap -> sheet
     * path skips both the server-wait and the nonce round trip. Safe to call
     * repeatedly; only one nonce is kept.
     */
    public void prefetchGoogleSignInNonce() {
        googleSignInFlow.prefetchSignInNonce();
    }

    /**
     * Runs nonce -> Credential Manager -> server exchange. The Activity is used
     * only for the Credential Manager call and is never retained.
     */
    public void googleSignIn(@NonNull android.content.Context activity) {
        if (currentState().getLoading() || !googleSignInFlow.isConfigured()) {
            return;
        }
        updateState(state -> state.withLoading(true, null));
        disposables.add(googleSignInFlow.signIn(activity)
                .subscribeOn(Schedulers.io())
                .subscribe(
                        this::finishGoogleSignIn,
                        error -> updateState(state -> state.withLoading(
                                false, new ApiError.Network(error.getMessage())))));
    }

    private void finishGoogleSignIn(Result<AuthSessionResult> result) {
        if (result instanceof Result.Failure<?> failure
                && GoogleSignInFlow.CODE_CANCELLED.equals(codeOf(failure.getError()))) {
            // Dismissing the Google sheet is not an error worth a banner.
            updateState(state -> state.withLoading(false, null));
            return;
        }
        finishGoogleSession(result);
    }

    private void finishGoogleSession(Result<AuthSessionResult> result) {
        if (result instanceof Result.Success<?> success) {
            AuthSessionResult session = (AuthSessionResult) success.getValue();
            updateState(state -> state.withLoading(false, null));
            if (session.isNewAccount()) {
                savedState.set(KEY_PENDING_COMPLETE_PROFILE, Boolean.TRUE);
                events.postValue(Event.CompleteProfile);
            } else {
                events.postValue(Event.LoggedIn);
            }
            return;
        }
        ApiError error = ((Result.Failure<?>) result).getError();
        updateState(state -> state.withLoading(false, error));
    }

    public boolean hasPendingCompleteProfile() {
        return Boolean.TRUE.equals(savedState.get(KEY_PENDING_COMPLETE_PROFILE));
    }

    /**
     * Clears the pending complete-profile navigation. Returns true only for the
     * first caller, so a restored one-shot event and the saved-state check
     * cannot both open the screen after rotation.
     */
    public boolean onCompleteProfileNavigationHandled() {
        if (!hasPendingCompleteProfile()) {
            return false;
        }
        savedState.remove(KEY_PENDING_COMPLETE_PROFILE);
        return true;
    }

    @Nullable
    private static String codeOf(ApiError error) {
        return error instanceof ApiError.Unknown unknown ? unknown.getCode() : null;
    }

    public LiveData<UiState> getUiState() {
        return mutableUiState;
    }

    public LiveData<Event> getEvents() {
        return events;
    }

    public LiveData<Boolean> getServerStarting() {
        return serverStarting;
    }

    public void onPhoneChange(@NonNull String value) {
        updateState(state -> state.withPhone(value));
    }

    public LiveData<EmailSignupState> getEmailSignup() {
        return mutableEmailSignup;
    }

    public void onEmailChange(@NonNull String value) {
        updateEmailSignup(state -> state.withEmail(value));
    }

    public void onSignupCodeChange(@NonNull String value) {
        updateEmailSignup(state -> state.withCode(PasswordResetOtpSanitizer.sanitize(value)));
    }

    /**
     * First step of email sign-up: checks the form, then mails a code to the address.
     * The account is created only when {@link #registerWithEmail} presents that code.
     */
    public void requestSignupCode() {
        UiState state = currentState();
        EmailSignupState signup = emailSignup();
        String email = signup.getEmail().trim();
        boolean emailInvalid = !AuthFieldValidators.isEmail(email);
        boolean passwordInvalid = state.getPassword().length() < MIN_PASSWORD_LENGTH;
        CompleteProfileDisplayNameRules.Problem nameProblem =
                CompleteProfileDisplayNameRules.problem(state.getDisplayName());
        if (emailInvalid || passwordInvalid || nameProblem != null) {
            updateEmailSignup(current -> current.withEmailError(emailInvalid));
            updateState(current -> current.withRegisterValidation(
                    false, passwordInvalid, nameProblem));
            return;
        }
        if (state.getLoading()) {
            return;
        }
        updateState(current -> current.withLoading(true, null));
        disposables.add(authRepository.requestEmailSignupCode(email)
                .subscribeOn(Schedulers.io())
                .subscribe(this::finishCodeRequest, this::failLoading));
    }

    public void resendSignupCode() {
        EmailSignupState signup = emailSignup();
        if (!signup.isCodeSent() || currentState().getLoading()
                || signup.resendSecondsRemaining(clock.millis()) > 0) {
            return;
        }
        updateState(current -> current.withLoading(true, null));
        disposables.add(authRepository.requestEmailSignupCode(signup.getEmail().trim())
                .subscribeOn(Schedulers.io())
                .subscribe(this::finishCodeRequest, this::failLoading));
    }

    /** Back to the form so a mistyped address can be corrected. */
    public void changeSignupEmail() {
        updateEmailSignup(current -> current.backToForm());
        updateState(current -> current.withLoading(false, null));
    }

    public void registerWithEmail() {
        UiState state = currentState();
        EmailSignupState signup = emailSignup();
        if (!signup.isCodeSent() || state.getLoading()) {
            return;
        }
        if (!PasswordResetOtpSanitizer.isComplete(signup.getCode())) {
            updateEmailSignup(current -> current.withCodeError(true));
            return;
        }
        String displayName = CompleteProfileDisplayNameRules.normalize(state.getDisplayName());
        updateState(current -> current.withLoading(true, null));
        disposables.add(authRepository.registerWithEmail(
                        signup.getEmail().trim(), signup.getCode(), state.getPassword(), displayName)
                .subscribeOn(Schedulers.io())
                .subscribe(this::finishEmailRegister, this::failLoading));
    }

    private void finishCodeRequest(Result<Unit> result) {
        if (result instanceof Result.Success<?>) {
            long until = clock.millis() + EmailSignupState.RESEND_COOLDOWN_MS;
            updateEmailSignup(current -> current.withCodeSent(until));
            updateState(current -> current.withLoading(false, null));
            return;
        }
        ApiError error = ((Result.Failure<?>) result).getError();
        updateState(current -> current.withLoading(false, error));
    }

    private void finishEmailRegister(Result<AuthResponse> result) {
        if (result instanceof Result.Failure<?> failure
                && failure.getError() instanceof ApiError.InvalidOrExpiredCode) {
            updateEmailSignup(current -> current.withCodeError(true));
            updateState(current -> current.withLoading(false, null));
            return;
        }
        finishSubmit(result);
    }

    private void failLoading(Throwable error) {
        updateState(state -> state.withLoading(false, new ApiError.Network(error.getMessage())));
    }

    public void onPasswordChange(@NonNull String value) {
        updateState(state -> state.withPassword(value));
    }

    public void onDisplayNameChange(@NonNull String value) {
        updateState(state -> state.withDisplayName(value));
    }

    public void login() {
        UiState state = currentState();
        boolean phoneInvalid = isBlank(state.getPhone());
        boolean passwordInvalid = state.getPassword().length() < MIN_PASSWORD_LENGTH;
        if (phoneInvalid || passwordInvalid) {
            updateState(current -> current.withLoginValidation(phoneInvalid, passwordInvalid));
            return;
        }
        submitLogin(state.getPhone(), state.getPassword());
    }

    public void register() {
        UiState state = currentState();
        boolean phoneInvalid = isBlank(state.getPhone());
        boolean passwordInvalid = state.getPassword().length() < MIN_PASSWORD_LENGTH;
        CompleteProfileDisplayNameRules.Problem nameProblem =
                CompleteProfileDisplayNameRules.problem(state.getDisplayName());
        if (phoneInvalid || passwordInvalid || nameProblem != null) {
            updateState(current -> current.withRegisterValidation(
                    phoneInvalid, passwordInvalid, nameProblem));
            return;
        }
        String displayName = CompleteProfileDisplayNameRules.normalize(state.getDisplayName());
        submitRegister(state.getPhone(), state.getPassword(), displayName);
    }

    private void submitLogin(@NonNull String phone, @NonNull String password) {
        if (currentState().getLoading()) {
            return; // double-tap / IME re-submit guard
        }
        updateState(state -> state.withLoading(true, null));
        disposables.add(authRepository.login(phone, password)
                .subscribeOn(Schedulers.io())
                .subscribe(
                        this::finishSubmit,
                        error -> updateState(state -> state.withLoading(
                                false, new ApiError.Network(error.getMessage())))));
    }

    private void submitRegister(
            @NonNull String phone, @NonNull String password, @NonNull String displayName) {
        if (currentState().getLoading()) {
            return; // double-tap / IME re-submit guard
        }
        updateState(state -> state.withLoading(true, null));
        disposables.add(authRepository.register(phone, password, displayName)
                .subscribeOn(Schedulers.io())
                .subscribe(
                        this::finishSubmit,
                        error -> updateState(state -> state.withLoading(
                                false, new ApiError.Network(error.getMessage())))));
    }

    private void finishSubmit(Result<AuthResponse> result) {
        if (result instanceof Result.Success<?>) {
            updateState(state -> state.withLoading(false, null));
            events.postValue(Event.LoggedIn);
        } else {
            ApiError error = ((Result.Failure<?>) result).getError();
            updateState(state -> state.withLoading(false, error));
        }
    }

    private synchronized UiState currentState() {
        return currentState;
    }

    private synchronized EmailSignupState emailSignup() {
        return emailSignupState;
    }

    private synchronized void updateEmailSignup(UnaryOperator<EmailSignupState> reducer) {
        emailSignupState = Objects.requireNonNull(reducer.apply(emailSignupState), "state");
        mutableEmailSignup.postValue(emailSignupState);
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

    private static boolean isBlank(@NonNull String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) {
                return false;
            }
        }
        return true;
    }

    /** State of the email sign-up steps: the address, the mailed code and the resend cooldown. */
    public static final class EmailSignupState {
        static final long RESEND_COOLDOWN_MS = 60_000L;

        @NonNull private final String email;
        @NonNull private final String code;
        private final boolean codeSent;
        private final boolean emailError;
        private final boolean codeError;
        private final long resendAtMs;

        public EmailSignupState() {
            this("", "", false, false, false, 0L);
        }

        private EmailSignupState(
                @NonNull String email,
                @NonNull String code,
                boolean codeSent,
                boolean emailError,
                boolean codeError,
                long resendAtMs) {
            this.email = email;
            this.code = code;
            this.codeSent = codeSent;
            this.emailError = emailError;
            this.codeError = codeError;
            this.resendAtMs = resendAtMs;
        }

        @NonNull public String getEmail() {
            return email;
        }

        @NonNull public String getCode() {
            return code;
        }

        public boolean isCodeSent() {
            return codeSent;
        }

        public boolean getEmailError() {
            return emailError;
        }

        public boolean getCodeError() {
            return codeError;
        }

        /** Whole seconds until another code may be requested; zero when it may be requested now. */
        public int resendSecondsRemaining(long nowMs) {
            long remaining = resendAtMs - nowMs;
            return remaining <= 0 ? 0 : (int) ((remaining + 999) / 1000);
        }

        EmailSignupState withEmail(@NonNull String value) {
            return new EmailSignupState(value, code, codeSent, false, codeError, resendAtMs);
        }

        EmailSignupState withCode(@NonNull String value) {
            return new EmailSignupState(email, value, codeSent, emailError, false, resendAtMs);
        }

        EmailSignupState withEmailError(boolean value) {
            return new EmailSignupState(email, code, codeSent, value, codeError, resendAtMs);
        }

        EmailSignupState withCodeError(boolean value) {
            return new EmailSignupState(email, code, codeSent, emailError, value, resendAtMs);
        }

        EmailSignupState withCodeSent(long resendAt) {
            return new EmailSignupState(email, "", true, false, false, resendAt);
        }

        EmailSignupState backToForm() {
            return new EmailSignupState(email, "", false, false, false, 0L);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof EmailSignupState that)) {
                return false;
            }
            return codeSent == that.codeSent
                    && emailError == that.emailError
                    && codeError == that.codeError
                    && resendAtMs == that.resendAtMs
                    && email.equals(that.email)
                    && code.equals(that.code);
        }

        @Override
        public int hashCode() {
            return Objects.hash(email, code, codeSent, emailError, codeError, resendAtMs);
        }

        @Override
        public String toString() {
            return "EmailSignupState(email=" + email
                    + ", code=<redacted>, codeSent=" + codeSent
                    + ", emailError=" + emailError
                    + ", codeError=" + codeError + ")";
        }
    }

    /** One-shot navigation events after authentication succeeds. */
    public enum Event {
        LoggedIn,
        CompleteProfile
    }

    /** Immutable observable state for both auth destinations. */
    public static final class UiState {
        @NonNull private final String phone;
        @NonNull private final String password;
        @NonNull private final String displayName;
        private final boolean loading;
        @Nullable private final ApiError error;
        private final boolean phoneError;
        private final boolean passwordError;
        @Nullable private final CompleteProfileDisplayNameRules.Problem displayNameProblem;

        public UiState() {
            this("", "", "", false, null, false, false, null);
        }

        public UiState(
                @NonNull String phone,
                @NonNull String password,
                @NonNull String displayName,
                boolean loading,
                @Nullable ApiError error,
                boolean phoneError,
                boolean passwordError,
                @Nullable CompleteProfileDisplayNameRules.Problem displayNameProblem) {
            this.phone = Objects.requireNonNull(phone, "phone");
            this.password = Objects.requireNonNull(password, "password");
            this.displayName = Objects.requireNonNull(displayName, "displayName");
            this.loading = loading;
            this.error = error;
            this.phoneError = phoneError;
            this.passwordError = passwordError;
            this.displayNameProblem = displayNameProblem;
        }

        @NonNull
        public String getPhone() {
            return phone;
        }

        @NonNull
        public String getPassword() {
            return password;
        }

        @NonNull
        public String getDisplayName() {
            return displayName;
        }

        public boolean getLoading() {
            return loading;
        }

        @Nullable
        public ApiError getError() {
            return error;
        }

        public boolean getPhoneError() {
            return phoneError;
        }

        public boolean getPasswordError() {
            return passwordError;
        }

        @Nullable
        public CompleteProfileDisplayNameRules.Problem getDisplayNameProblem() {
            return displayNameProblem;
        }

        public UiState withPhone(@NonNull String value) {
            return new UiState(
                    value, password, displayName, loading, error, false, passwordError,
                    displayNameProblem);
        }

        public UiState withPassword(@NonNull String value) {
            return new UiState(
                    phone, value, displayName, loading, error, phoneError, false,
                    displayNameProblem);
        }

        public UiState withDisplayName(@NonNull String value) {
            return new UiState(
                    phone, password, value, loading, error, phoneError, passwordError, null);
        }

        public UiState withLoginValidation(boolean phoneInvalid, boolean passwordInvalid) {
            return new UiState(
                    phone, password, displayName, loading, null, phoneInvalid, passwordInvalid,
                    displayNameProblem);
        }

        public UiState withRegisterValidation(
                boolean phoneInvalid,
                boolean passwordInvalid,
                @Nullable CompleteProfileDisplayNameRules.Problem nameProblem) {
            return new UiState(
                    phone, password, displayName, loading, null, phoneInvalid, passwordInvalid,
                    nameProblem);
        }

        public UiState withLoading(boolean value, @Nullable ApiError newError) {
            return new UiState(
                    phone, password, displayName, value, newError, phoneError, passwordError,
                    displayNameProblem);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof UiState state)) {
                return false;
            }
            return loading == state.loading
                    && phoneError == state.phoneError
                    && passwordError == state.passwordError
                    && displayNameProblem == state.displayNameProblem
                    && phone.equals(state.phone)
                    && password.equals(state.password)
                    && displayName.equals(state.displayName)
                    && Objects.equals(error, state.error);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    phone, password, displayName, loading, error, phoneError, passwordError,
                    displayNameProblem);
        }

        @Override
        public String toString() {
            return "UiState(phone=" + phone
                    + ", password=<redacted>"
                    + ", displayName=" + displayName
                    + ", loading=" + loading
                    + ", error=" + error
                    + ", phoneError=" + phoneError
                    + ", passwordError=" + passwordError
                    + ", displayNameProblem=" + displayNameProblem
                    + ")";
        }
    }
}

/** LiveData event stream that delivers each value to one active observer cycle. */
final class OneShotLiveData<T> extends MutableLiveData<T> {

    private final AtomicBoolean pending = new AtomicBoolean(false);
    private final java.util.Map<androidx.lifecycle.Observer<? super T>,
            androidx.lifecycle.Observer<T>> foreverObservers = new java.util.IdentityHashMap<>();

    @Override
    public void observe(
            @NonNull androidx.lifecycle.LifecycleOwner owner,
            @NonNull androidx.lifecycle.Observer<? super T> observer) {
        super.observe(owner, gated(observer));
    }

    @Override
    public void observeForever(@NonNull androidx.lifecycle.Observer<? super T> observer) {
        androidx.lifecycle.Observer<T> wrapper = gated(observer);
        synchronized (foreverObservers) {
            foreverObservers.put(observer, wrapper);
        }
        super.observeForever(wrapper);
    }

    @Override
    public void removeObserver(@NonNull androidx.lifecycle.Observer<? super T> observer) {
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
