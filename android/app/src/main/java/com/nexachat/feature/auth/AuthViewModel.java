package com.nexachat.feature.auth;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelKt;
import com.nexachat.core.common.ApiError;
import com.nexachat.data.AuthRepository;
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
 * Shared ViewModel for the Login and Register destinations (each nav entry
 * gets its own instance via {@code hiltViewModel()} — no state leaks between them).
 *
 * <p>Field inputs live in {@link UiState} so they survive config changes;
 * one-shot navigation uses a buffered channel.
 */
@HiltViewModel
public final class AuthViewModel extends ViewModel {

    public static final int MIN_PASSWORD_LENGTH = 8;

    private final AuthRepository authRepository;
    private final MutableStateFlow<UiState> mutableUiState =
            StateFlowKt.MutableStateFlow(new UiState());
    private final StateFlow<UiState> uiState =
            AuthViewModelSupportKt.authReadOnlyUiState(mutableUiState);
    private final Channel<Event> eventsChannel = AuthViewModelSupportKt.newAuthEventChannel();
    private final Flow<Event> events = AuthViewModelSupportKt.authEventsFlow(eventsChannel);

    @Inject
    public AuthViewModel(AuthRepository authRepository) {
        this.authRepository = Objects.requireNonNull(authRepository, "authRepository");
    }

    public StateFlow<UiState> getUiState() {
        return uiState;
    }

    public Flow<Event> getEvents() {
        return events;
    }

    public void onPhoneChange(@NonNull String value) {
        mutableUiState.setValue(mutableUiState.getValue().withPhone(value));
    }

    public void onPasswordChange(@NonNull String value) {
        mutableUiState.setValue(mutableUiState.getValue().withPassword(value));
    }

    public void onDisplayNameChange(@NonNull String value) {
        mutableUiState.setValue(mutableUiState.getValue().withDisplayName(value));
    }

    public void login() {
        UiState state = mutableUiState.getValue();
        boolean phoneInvalid = isBlank(state.getPhone());
        boolean passwordInvalid = state.getPassword().length() < MIN_PASSWORD_LENGTH;
        if (phoneInvalid || passwordInvalid) {
            mutableUiState.setValue(state.withLoginValidation(phoneInvalid, passwordInvalid));
            return;
        }
        submitLogin(state.getPhone(), state.getPassword());
    }

    public void register() {
        UiState state = mutableUiState.getValue();
        boolean phoneInvalid = isBlank(state.getPhone());
        boolean passwordInvalid = state.getPassword().length() < MIN_PASSWORD_LENGTH;
        boolean nameInvalid = isBlank(state.getDisplayName());
        if (phoneInvalid || passwordInvalid || nameInvalid) {
            mutableUiState.setValue(
                    state.withRegisterValidation(phoneInvalid, passwordInvalid, nameInvalid));
            return;
        }
        submitRegister(state.getPhone(), state.getPassword(), state.getDisplayName());
    }

    private void submitLogin(@NonNull String phone, @NonNull String password) {
        if (mutableUiState.getValue().getLoading()) {
            return; // double-tap / IME re-submit guard
        }
        launch(
                (scope, continuation) ->
                        AuthViewModelSupportKt.authSubmitLogin(
                                authRepository, phone, password, mutableUiState, eventsChannel,
                                continuation));
    }

    private void submitRegister(
            @NonNull String phone, @NonNull String password, @NonNull String displayName) {
        if (mutableUiState.getValue().getLoading()) {
            return; // double-tap / IME re-submit guard
        }
        launch(
                (scope, continuation) ->
                        AuthViewModelSupportKt.authSubmitRegister(
                                authRepository, phone, password, displayName,
                                mutableUiState, eventsChannel, continuation));
    }

    private void launch(Function2<CoroutineScope, Continuation<? super Unit>, Object> block) {
        BuildersKt.launch(
                ViewModelKt.getViewModelScope(this),
                EmptyCoroutineContext.INSTANCE,
                CoroutineStart.DEFAULT,
                block);
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
                    + ", password=" + password
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
