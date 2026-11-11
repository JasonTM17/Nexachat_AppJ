package com.nexachat.feature.auth;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.nexachat.core.auth.GoogleSignInFlow;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.dto.AuthResponse;
import com.nexachat.data.AuthRepository;
import dagger.hilt.android.lifecycle.HiltViewModel;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import javax.inject.Inject;
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

    private final AuthRepository authRepository;
    private final GoogleSignInFlow googleSignInFlow;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<UiState> mutableUiState = new MutableLiveData<>(new UiState());
    private final OneShotLiveData<Event> events = new OneShotLiveData<>();
    private UiState currentState = mutableUiState.getValue();

    @Inject
    public AuthViewModel(AuthRepository authRepository, GoogleSignInFlow googleSignInFlow) {
        this.authRepository = Objects.requireNonNull(authRepository, "authRepository");
        this.googleSignInFlow = Objects.requireNonNull(googleSignInFlow, "googleSignInFlow");
    }

    /** True when the build carries a Google web client ID; otherwise the button stays hidden. */
    public boolean isGoogleSignInAvailable() {
        return googleSignInFlow.isConfigured();
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

    private void finishGoogleSignIn(Result<AuthResponse> result) {
        if (result instanceof Result.Failure<?> failure
                && GoogleSignInFlow.CODE_CANCELLED.equals(codeOf(failure.getError()))) {
            // Dismissing the Google sheet is not an error worth a banner.
            updateState(state -> state.withLoading(false, null));
            return;
        }
        finishSubmit(result);
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

    public void onPhoneChange(@NonNull String value) {
        updateState(state -> state.withPhone(value));
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
        boolean nameInvalid = isBlank(state.getDisplayName());
        if (phoneInvalid || passwordInvalid || nameInvalid) {
            updateState(current -> current.withRegisterValidation(
                    phoneInvalid, passwordInvalid, nameInvalid));
            return;
        }
        submitRegister(state.getPhone(), state.getPassword(), state.getDisplayName());
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

    /** The single navigation event emitted after either login or registration succeeds. */
    public enum Event {
        LoggedIn
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
        private final boolean displayNameError;

        public UiState() {
            this("", "", "", false, null, false, false, false);
        }

        public UiState(
                @NonNull String phone,
                @NonNull String password,
                @NonNull String displayName,
                boolean loading,
                @Nullable ApiError error,
                boolean phoneError,
                boolean passwordError,
                boolean displayNameError) {
            this.phone = Objects.requireNonNull(phone, "phone");
            this.password = Objects.requireNonNull(password, "password");
            this.displayName = Objects.requireNonNull(displayName, "displayName");
            this.loading = loading;
            this.error = error;
            this.phoneError = phoneError;
            this.passwordError = passwordError;
            this.displayNameError = displayNameError;
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

        public boolean getDisplayNameError() {
            return displayNameError;
        }

        public UiState withPhone(@NonNull String value) {
            return new UiState(
                    value, password, displayName, loading, error, false, passwordError,
                    displayNameError);
        }

        public UiState withPassword(@NonNull String value) {
            return new UiState(
                    phone, value, displayName, loading, error, phoneError, false,
                    displayNameError);
        }

        public UiState withDisplayName(@NonNull String value) {
            return new UiState(
                    phone, password, value, loading, error, phoneError, passwordError, false);
        }

        public UiState withLoginValidation(boolean phoneInvalid, boolean passwordInvalid) {
            return new UiState(
                    phone, password, displayName, loading, null, phoneInvalid, passwordInvalid,
                    displayNameError);
        }

        public UiState withRegisterValidation(
                boolean phoneInvalid, boolean passwordInvalid, boolean nameInvalid) {
            return new UiState(
                    phone, password, displayName, loading, null, phoneInvalid, passwordInvalid,
                    nameInvalid);
        }

        public UiState withLoading(boolean value, @Nullable ApiError newError) {
            return new UiState(
                    phone, password, displayName, value, newError, phoneError, passwordError,
                    displayNameError);
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
                    && displayNameError == state.displayNameError
                    && phone.equals(state.phone)
                    && password.equals(state.password)
                    && displayName.equals(state.displayName)
                    && Objects.equals(error, state.error);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    phone, password, displayName, loading, error, phoneError, passwordError,
                    displayNameError);
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
                    + ", displayNameError=" + displayNameError
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
