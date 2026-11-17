package com.nexachat.feature.profile;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.data.AuthRepository;
import com.nexachat.data.ContactsRepository;
import com.nexachat.data.SettingsRepository;
import com.nexachat.feature.auth.AuthFieldValidators;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.function.Consumer;
import javax.inject.Inject;

/**
 * Profile screen state and actions. Logout is broadcast by AuthRepository and
 * handled by the AppNavHost-level collector, which clears the navigation stack.
 */
@HiltViewModel
public final class ProfileViewModel extends ViewModel {

    private final ContactsRepository contactsRepository;
    private final AuthRepository authRepository;
    private final SettingsRepository settingsRepository;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<UiState> mutableUiState = new MutableLiveData<>(new UiState());
    private final MutableLiveData<Boolean> showPresence = new MutableLiveData<>(true);
    private final MutableLiveData<Boolean> showSeen = new MutableLiveData<>(true);
    private UiState currentState = mutableUiState.getValue();
    private long sessionGeneration;

    @Inject
    public ProfileViewModel(
            ContactsRepository contactsRepository,
            AuthRepository authRepository,
            SettingsRepository settingsRepository,
            SessionEvents sessionEvents) {
        this(contactsRepository, authRepository, settingsRepository);
        // This state is activity-scoped, so it survives a logout to login
        // transition; the profile must follow the session, not the screen.
        disposables.add(Objects.requireNonNull(sessionEvents, "sessionEvents")
                .getEvents()
                .subscribe(event -> {
                    if (event instanceof SessionEvents.Event.LoggedOut
                            || event instanceof SessionEvents.Event.ReauthenticationRequired) {
                        resetForSignedOutSession();
                    }
                }, error -> { }));
    }

    /** Test seam without session events. */
    public ProfileViewModel(
            ContactsRepository contactsRepository,
            AuthRepository authRepository,
            SettingsRepository settingsRepository) {
        this.contactsRepository = Objects.requireNonNull(contactsRepository, "contactsRepository");
        this.authRepository = Objects.requireNonNull(authRepository, "authRepository");
        this.settingsRepository = Objects.requireNonNull(settingsRepository, "settingsRepository");
        disposables.add(settingsRepository.getShowPresence()
                .subscribeOn(Schedulers.io())
                .subscribe(showPresence::postValue, error -> { }));
        disposables.add(settingsRepository.getShowSeen()
                .subscribeOn(Schedulers.io())
                .subscribe(showSeen::postValue, error -> { }));
        load();
    }

    public LiveData<UiState> getUiState() {
        return mutableUiState;
    }

    /** Presence privacy toggle (F3), backed by the settings repository. */
    public LiveData<Boolean> getShowPresence() {
        return showPresence;
    }

    /** Seen privacy toggle, backed by the settings repository. */
    public LiveData<Boolean> getShowSeen() {
        return showSeen;
    }

    public void load() {
        long generation = currentSessionGeneration();
        updateStateIfCurrent(generation,
                state -> state.withLoading(true).withError(null));
        disposables.add(contactsRepository.getMe()
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> updateStateIfCurrent(generation, state ->
                                result instanceof Result.Success<?> success
                                        ? state.withLoading(false).withUser((UserDto) success.getValue())
                                        : state.withLoading(false).withError(
                                                ((Result.Failure<?>) result).getError())),
                        error -> updateStateIfCurrent(generation, state -> state.withLoading(false)
                                .withError(new ApiError.Network(error.getMessage())))));
    }

    public void startEditName() {
        UiState state = currentState();
        UserDto user = state.getUser();
        String displayName = user == null ? "" : user.getDisplayName();
        updateState(current -> current
                .withEditingName(true)
                .withNameInput(displayName == null ? "" : displayName)
                .withSaveError(null));
    }

    public void onNameInputChange(String value) {
        Objects.requireNonNull(value, "value");
        updateState(state -> state
                .withNameInput(value)
                .withSaveError(null));
    }

    public void cancelEditName() {
        updateState(state -> state
                .withEditingName(false)
                .withNameInput("")
                .withSaveError(null));
    }

    public void saveDisplayName() {
        UiState state = currentState();
        String name = trimWhitespace(state.getNameInput());
        if (name.isEmpty() || state.isSaving()) {
            return;
        }
        long generation = currentSessionGeneration();
        updateStateIfCurrent(generation,
                current -> current.withSaving(true).withSaveError(null));
        disposables.add(contactsRepository.updateDisplayName(name)
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> updateStateIfCurrent(generation, current ->
                                result instanceof Result.Success<?> success
                                        ? current.withSaving(false).withEditingName(false)
                                                .withUser((UserDto) success.getValue())
                                        : current.withSaving(false).withSaveError(
                                                ((Result.Failure<?>) result).getError())),
                        error -> updateStateIfCurrent(generation, current -> current.withSaving(false)
                                .withSaveError(new ApiError.Network(error.getMessage())))));
    }

    public void openPasswordDialog() {
        if (!currentState().canChangePassword()) return;
        updateState(state -> state
                .withShowPasswordDialog(true)
                .withOldPassword("")
                .withNewPassword("")
                .withConfirmPassword("")
                .withOldPasswordError(false)
                .withNewPasswordError(false)
                .withConfirmMismatch(false)
                .withPasswordError(null)
                .withPasswordChanged(false));
    }

    public void dismissPasswordDialog() {
        updateState(state -> state
                .withShowPasswordDialog(false)
                .withOldPassword("")
                .withNewPassword("")
                .withConfirmPassword("")
                .withPasswordError(null));
    }

    public void onOldPasswordChange(String value) {
        Objects.requireNonNull(value, "value");
        updateState(state -> state
                .withOldPassword(value)
                .withOldPasswordError(false)
                .withPasswordError(null));
    }

    public void onNewPasswordChange(String value) {
        Objects.requireNonNull(value, "value");
        updateState(state -> state
                .withNewPassword(value)
                .withNewPasswordError(false)
                .withPasswordError(null));
    }

    public void onConfirmPasswordChange(String value) {
        Objects.requireNonNull(value, "value");
        updateState(state -> state
                .withConfirmPassword(value)
                .withConfirmMismatch(false)
                .withPasswordError(null));
    }

    public void consumePasswordChanged() {
        updateState(state -> state.withPasswordChanged(false));
    }

    /** Opens the phone dialog prefilled with the current number, if any. */
    public void openPhoneDialog() {
        UserDto user = currentState().getUser();
        if (user == null) {
            return;
        }
        String phone = user.getPhone();
        updateState(state -> state
                .withShowPhoneDialog(true)
                .withPhoneInput(phone == null ? "" : phone)
                .withPhonePassword("")
                .withPhoneInvalid(false)
                .withPhonePasswordMissing(false)
                .withPhoneError(null));
    }

    public void dismissPhoneDialog() {
        updateState(state -> state
                .withShowPhoneDialog(false)
                .withPhoneInput("")
                .withPhonePassword("")
                .withPhoneInvalid(false)
                .withPhonePasswordMissing(false)
                .withPhoneError(null));
    }

    public void onPhoneInputChange(String value) {
        Objects.requireNonNull(value, "value");
        updateState(state -> state
                .withPhoneInput(value)
                .withPhoneInvalid(false)
                .withPhoneError(null));
    }

    public void onPhonePasswordChange(String value) {
        Objects.requireNonNull(value, "value");
        updateState(state -> state
                .withPhonePassword(value)
                .withPhonePasswordMissing(false)
                .withPhoneError(null));
    }

    /**
     * Validates the typed number exactly like Register ({@code normalizeE164}
     * then {@code isE164}) before issuing the link call; an invalid input
     * flags the field and never reaches the repository. Replacing a linked
     * phone additionally requires the current password — a blank one flags
     * its field and blocks the call just like an invalid number.
     */
    public void submitPhoneChange() {
        UiState state = currentState();
        if (state.isPhoneSaving()) {
            return;
        }
        String normalized = AuthFieldValidators.normalizeE164(state.getPhoneInput());
        if (!AuthFieldValidators.isE164(normalized)) {
            updateState(current -> current.withPhoneInvalid(true));
            return;
        }
        String password = null;
        if (state.isPhonePasswordRequired()) {
            if (state.getPhonePassword().isBlank()) {
                updateState(current -> current.withPhonePasswordMissing(true));
                return;
            }
            password = state.getPhonePassword();
        }
        long generation = currentSessionGeneration();
        updateStateIfCurrent(generation,
                current -> current.withPhoneSaving(true).withPhoneError(null)
                        .withPhonePasswordMissing(false));
        disposables.add(contactsRepository.linkPhone(normalized, password)
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> updateStateIfCurrent(generation, current ->
                                result instanceof Result.Success<?> success
                                        ? current.withPhoneSaving(false)
                                                .withShowPhoneDialog(false)
                                                .withPhoneInput("")
                                                .withPhonePassword("")
                                                .withUser((UserDto) success.getValue())
                                        : current.withPhoneSaving(false).withPhoneError(
                                                ((Result.Failure<?>) result).getError())),
                        error -> updateStateIfCurrent(generation, current -> current
                                .withPhoneSaving(false)
                                .withPhoneError(new ApiError.Network(error.getMessage())))));
    }

    /** Removes the stored phone after the fragment-level confirmation. */
    public void removePhone() {
        UiState state = currentState();
        if (state.isPhoneSaving()) {
            return;
        }
        long generation = currentSessionGeneration();
        updateStateIfCurrent(generation,
                current -> current.withPhoneSaving(true).withPhoneError(null));
        disposables.add(contactsRepository.removePhone()
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> updateStateIfCurrent(generation, current ->
                                result instanceof Result.Success<?> success
                                        ? current.withPhoneSaving(false)
                                                .withShowPhoneDialog(false)
                                                .withPhoneInput("")
                                                .withPhonePassword("")
                                                .withUser((UserDto) success.getValue())
                                        : current.withPhoneSaving(false).withPhoneError(
                                                ((Result.Failure<?>) result).getError())),
                        error -> updateStateIfCurrent(generation, current -> current
                                .withPhoneSaving(false)
                                .withPhoneError(new ApiError.Network(error.getMessage())))));
    }

    public void submitPasswordChange() {
        UiState state = currentState();
        if (state.isPasswordSaving()) {
            return;
        }
        String oldPassword = state.getOldPassword();
        String newPassword = state.getNewPassword();
        boolean oldBad = oldPassword.isEmpty();
        boolean newBad = newPassword.length() < 8;
        boolean mismatch = !newPassword.equals(state.getConfirmPassword());
        if (oldBad || newBad || mismatch) {
            updateState(current -> current
                    .withOldPasswordError(oldBad)
                    .withNewPasswordError(newBad)
                    .withConfirmMismatch(mismatch));
            return;
        }
        updateState(current -> current.withPasswordSaving(true).withPasswordError(null));
        disposables.add(authRepository.changePassword(oldPassword, newPassword)
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> updateState(current -> result instanceof Result.Success<?>
                                ? current.withPasswordSaving(false).withShowPasswordDialog(false)
                                        .withOldPassword("")
                                        .withNewPassword("")
                                        .withConfirmPassword("")
                                        .withPasswordChanged(true)
                                : current.withPasswordSaving(false).withPasswordError(
                                        ((Result.Failure<?>) result).getError())),
                        error -> updateState(current -> current.withPasswordSaving(false)
                                .withPasswordError(new ApiError.Network(error.getMessage())))));
    }

    /** Uploads the selected avatar and stores the server-refreshed user record. */
    public void uploadAvatar(Uri uri) {
        Objects.requireNonNull(uri, "uri");
        if (currentState().isAvatarUploading()) {
            return;
        }
        long generation = currentSessionGeneration();
        updateStateIfCurrent(generation,
                state -> state.withAvatarUploading(true).withAvatarError(null));
        disposables.add(contactsRepository.uploadAvatar(uri)
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> updateStateIfCurrent(generation, state ->
                                result instanceof Result.Success<?> success
                                        ? state.withAvatarUploading(false)
                                                .withUser((UserDto) success.getValue())
                                        : state.withAvatarUploading(false).withAvatarError(
                                                ((Result.Failure<?>) result).getError())),
                        error -> updateStateIfCurrent(generation, state -> state.withAvatarUploading(false)
                                .withAvatarError(new ApiError.Network(error.getMessage())))));
    }

    public void consumeAvatarError() {
        updateState(state -> state.withAvatarError(null));
    }

    /** The repository applies the presence change optimistically and reverts on failure. */
    public void setShowPresence(boolean value) {
        disposables.add(settingsRepository.setShowPresence(value)
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> updateState(state -> result instanceof Result.Success<?>
                                ? state.withSaveError(null)
                                : state.withSaveError(((Result.Failure<?>) result).getError())),
                        error -> updateState(state -> state.withSaveError(
                                new ApiError.Network(error.getMessage())))));
    }

    /** The repository applies the seen change optimistically and reverts on failure. */
    public void setShowSeen(boolean value) {
        disposables.add(settingsRepository.setShowSeen(value)
                .subscribeOn(Schedulers.io())
                .subscribe(
                        result -> updateState(state -> result instanceof Result.Success<?>
                                ? state.withSaveError(null)
                                : state.withSaveError(((Result.Failure<?>) result).getError())),
                        error -> updateState(state -> state.withSaveError(
                                new ApiError.Network(error.getMessage())))));
    }

    /** Drops the previous account's cached state when the session ends. */
    private void resetForSignedOutSession() {
        // The bump shares the state lock, so a result from the dead session cannot
        // slip between the reset and the generation check that drops stale writes.
        synchronized (this) {
            sessionGeneration++;
            updateState(state -> new UiState().withLoading(false));
        }
    }

    /** Clears the local session and broadcasts LoggedOut. */
    public void logout() {
        disposables.add(authRepository.logout()
                .subscribeOn(Schedulers.io())
                .subscribe(ignored -> { }, error -> { }));
    }

    /** Revokes every refresh token, then clears the local session. */
    public void logoutAll() {
        disposables.add(authRepository.logoutAll()
                .subscribeOn(Schedulers.io())
                .subscribe(ignored -> { }, error -> { }));
    }

    private synchronized UiState currentState() {
        return currentState;
    }

    private synchronized void updateState(UnaryOperator<UiState> reducer) {
        currentState = Objects.requireNonNull(reducer.apply(currentState), "state");
        mutableUiState.postValue(currentState);
    }

    private synchronized long currentSessionGeneration() {
        return sessionGeneration;
    }

    /**
     * Applies a request's updates only while the session that started it is still live. A
     * result landing after a sign-out reset belongs to the dead session and must not
     * re-cache the previous account's identity on the fresh state.
     */
    private synchronized void updateStateIfCurrent(
            long generation, UnaryOperator<UiState> reducer) {
        if (generation != sessionGeneration) {
            return;
        }
        updateState(reducer);
    }

    @Override
    protected void onCleared() {
        disposables.clear();
        super.onCleared();
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

    /** Immutable render state for the profile screen. */
    public static final class UiState {

        private final boolean loading;
        @Nullable private final UserDto user;
        @Nullable private final ApiError error;
        private final boolean editingName;
        private final String nameInput;
        private final boolean saving;
        @Nullable private final ApiError saveError;
        private final boolean showPasswordDialog;
        private final String oldPassword;
        private final String newPassword;
        private final String confirmPassword;
        private final boolean oldPasswordError;
        private final boolean newPasswordError;
        private final boolean confirmMismatch;
        private final boolean passwordSaving;
        @Nullable private final ApiError passwordError;
        private final boolean passwordChanged;
        private final boolean avatarUploading;
        @Nullable private final ApiError avatarError;
        private final boolean showPhoneDialog;
        private final String phoneInput;
        private final String phonePassword;
        private final boolean phoneInvalid;
        private final boolean phonePasswordMissing;
        private final boolean phoneSaving;
        @Nullable private final ApiError phoneError;

        public UiState() {
            this(new Builder());
        }

        private UiState(Builder builder) {
            loading = builder.loading;
            user = builder.user;
            error = builder.error;
            editingName = builder.editingName;
            nameInput = Objects.requireNonNull(builder.nameInput, "nameInput");
            saving = builder.saving;
            saveError = builder.saveError;
            showPasswordDialog = builder.showPasswordDialog;
            oldPassword = Objects.requireNonNull(builder.oldPassword, "oldPassword");
            newPassword = Objects.requireNonNull(builder.newPassword, "newPassword");
            confirmPassword = Objects.requireNonNull(builder.confirmPassword, "confirmPassword");
            oldPasswordError = builder.oldPasswordError;
            newPasswordError = builder.newPasswordError;
            confirmMismatch = builder.confirmMismatch;
            passwordSaving = builder.passwordSaving;
            passwordError = builder.passwordError;
            passwordChanged = builder.passwordChanged;
            avatarUploading = builder.avatarUploading;
            avatarError = builder.avatarError;
            showPhoneDialog = builder.showPhoneDialog;
            phoneInput = Objects.requireNonNull(builder.phoneInput, "phoneInput");
            phonePassword = Objects.requireNonNull(builder.phonePassword, "phonePassword");
            phoneInvalid = builder.phoneInvalid;
            phonePasswordMissing = builder.phonePasswordMissing;
            phoneSaving = builder.phoneSaving;
            phoneError = builder.phoneError;
        }

        private UiState change(Consumer<Builder> update) {
            Builder builder = new Builder(this);
            update.accept(builder);
            return new UiState(builder);
        }

        public boolean isLoading() { return loading; }
        public boolean getLoading() { return loading; }
        @Nullable public UserDto getUser() { return user; }
        public boolean canChangePassword() { return user != null && user.isPasswordSet(); }
        @Nullable public ApiError getError() { return error; }
        public boolean isEditingName() { return editingName; }
        public boolean getEditingName() { return editingName; }
        public String getNameInput() { return nameInput; }
        public boolean isSaving() { return saving; }
        public boolean getSaving() { return saving; }
        @Nullable public ApiError getSaveError() { return saveError; }
        public boolean isShowPasswordDialog() { return showPasswordDialog; }
        public boolean getShowPasswordDialog() { return showPasswordDialog; }
        public String getOldPassword() { return oldPassword; }
        public String getNewPassword() { return newPassword; }
        public String getConfirmPassword() { return confirmPassword; }
        public boolean isOldPasswordError() { return oldPasswordError; }
        public boolean getOldPasswordError() { return oldPasswordError; }
        public boolean isNewPasswordError() { return newPasswordError; }
        public boolean getNewPasswordError() { return newPasswordError; }
        public boolean isConfirmMismatch() { return confirmMismatch; }
        public boolean getConfirmMismatch() { return confirmMismatch; }
        public boolean isPasswordSaving() { return passwordSaving; }
        public boolean getPasswordSaving() { return passwordSaving; }
        @Nullable public ApiError getPasswordError() { return passwordError; }
        public boolean isPasswordChanged() { return passwordChanged; }
        public boolean getPasswordChanged() { return passwordChanged; }
        public boolean isAvatarUploading() { return avatarUploading; }
        public boolean getAvatarUploading() { return avatarUploading; }
        @Nullable public ApiError getAvatarError() { return avatarError; }
        public boolean isShowPhoneDialog() { return showPhoneDialog; }
        public boolean getShowPhoneDialog() { return showPhoneDialog; }
        public String getPhoneInput() { return phoneInput; }
        public String getPhonePassword() { return phonePassword; }
        public boolean isPhoneInvalid() { return phoneInvalid; }
        public boolean getPhoneInvalid() { return phoneInvalid; }
        public boolean isPhonePasswordMissing() { return phonePasswordMissing; }
        public boolean getPhonePasswordMissing() { return phonePasswordMissing; }
        public boolean isPhoneSaving() { return phoneSaving; }
        public boolean getPhoneSaving() { return phoneSaving; }
        @Nullable public ApiError getPhoneError() { return phoneError; }

        /**
         * Replacing an already-linked phone re-authenticates with the current
         * account password; first links and accounts without a password
         * (Google-only sign-in) never ask for one.
         */
        public boolean isPhonePasswordRequired() {
            return user != null
                    && user.isPasswordSet()
                    && user.getPhone() != null
                    && !user.getPhone().isBlank();
        }

        /** Save stays disabled until the number is valid and a required password is non-blank. */
        public boolean canSubmitPhone() {
            if (!AuthFieldValidators.isE164(AuthFieldValidators.normalizeE164(phoneInput))) {
                return false;
            }
            return !isPhonePasswordRequired() || !phonePassword.isBlank();
        }

        public UiState withLoading(boolean value) {
            return change(builder -> builder.loading = value);
        }

        public UiState withUser(@Nullable UserDto value) {
            return change(builder -> builder.user = value);
        }

        public UiState withError(@Nullable ApiError value) {
            return change(builder -> builder.error = value);
        }

        public UiState withEditingName(boolean value) {
            return change(builder -> builder.editingName = value);
        }

        public UiState withNameInput(String value) {
            return change(builder -> builder.nameInput = value);
        }

        public UiState withSaving(boolean value) {
            return change(builder -> builder.saving = value);
        }

        public UiState withSaveError(@Nullable ApiError value) {
            return change(builder -> builder.saveError = value);
        }

        public UiState withShowPasswordDialog(boolean value) {
            return change(builder -> builder.showPasswordDialog = value);
        }

        public UiState withOldPassword(String value) {
            return change(builder -> builder.oldPassword = value);
        }

        public UiState withNewPassword(String value) {
            return change(builder -> builder.newPassword = value);
        }

        public UiState withConfirmPassword(String value) {
            return change(builder -> builder.confirmPassword = value);
        }

        public UiState withOldPasswordError(boolean value) {
            return change(builder -> builder.oldPasswordError = value);
        }

        public UiState withNewPasswordError(boolean value) {
            return change(builder -> builder.newPasswordError = value);
        }

        public UiState withConfirmMismatch(boolean value) {
            return change(builder -> builder.confirmMismatch = value);
        }

        public UiState withPasswordSaving(boolean value) {
            return change(builder -> builder.passwordSaving = value);
        }

        public UiState withPasswordError(@Nullable ApiError value) {
            return change(builder -> builder.passwordError = value);
        }

        public UiState withPasswordChanged(boolean value) {
            return change(builder -> builder.passwordChanged = value);
        }

        public UiState withAvatarUploading(boolean value) {
            return change(builder -> builder.avatarUploading = value);
        }

        public UiState withAvatarError(@Nullable ApiError value) {
            return change(builder -> builder.avatarError = value);
        }

        public UiState withShowPhoneDialog(boolean value) {
            return change(builder -> builder.showPhoneDialog = value);
        }

        public UiState withPhoneInput(String value) {
            return change(builder -> builder.phoneInput = value);
        }

        public UiState withPhonePassword(String value) {
            return change(builder -> builder.phonePassword = value);
        }

        public UiState withPhoneInvalid(boolean value) {
            return change(builder -> builder.phoneInvalid = value);
        }

        public UiState withPhonePasswordMissing(boolean value) {
            return change(builder -> builder.phonePasswordMissing = value);
        }

        public UiState withPhoneSaving(boolean value) {
            return change(builder -> builder.phoneSaving = value);
        }

        public UiState withPhoneError(@Nullable ApiError value) {
            return change(builder -> builder.phoneError = value);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof UiState that)) {
                return false;
            }
            return loading == that.loading
                    && editingName == that.editingName
                    && saving == that.saving
                    && showPasswordDialog == that.showPasswordDialog
                    && oldPasswordError == that.oldPasswordError
                    && newPasswordError == that.newPasswordError
                    && confirmMismatch == that.confirmMismatch
                    && passwordSaving == that.passwordSaving
                    && passwordChanged == that.passwordChanged
                    && avatarUploading == that.avatarUploading
                    && showPhoneDialog == that.showPhoneDialog
                    && phoneInvalid == that.phoneInvalid
                    && phonePasswordMissing == that.phonePasswordMissing
                    && phoneSaving == that.phoneSaving
                    && Objects.equals(user, that.user)
                    && Objects.equals(error, that.error)
                    && nameInput.equals(that.nameInput)
                    && Objects.equals(saveError, that.saveError)
                    && oldPassword.equals(that.oldPassword)
                    && newPassword.equals(that.newPassword)
                    && confirmPassword.equals(that.confirmPassword)
                    && Objects.equals(passwordError, that.passwordError)
                    && Objects.equals(avatarError, that.avatarError)
                    && phoneInput.equals(that.phoneInput)
                    && phonePassword.equals(that.phonePassword)
                    && Objects.equals(phoneError, that.phoneError);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    loading,
                    user,
                    error,
                    editingName,
                    nameInput,
                    saving,
                    saveError,
                    showPasswordDialog,
                    oldPassword,
                    newPassword,
                    confirmPassword,
                    oldPasswordError,
                    newPasswordError,
                    confirmMismatch,
                    passwordSaving,
                    passwordError,
                    passwordChanged,
                    avatarUploading,
                    avatarError,
                    showPhoneDialog,
                    phoneInput,
                    phonePassword,
                    phoneInvalid,
                    phonePasswordMissing,
                    phoneSaving,
                    phoneError);
        }

        @Override
        public String toString() {
            return "UiState(loading=" + loading
                    + ", user=" + user
                    + ", error=" + error
                    + ", editingName=" + editingName
                    + ", nameInput=" + nameInput
                    + ", saving=" + saving
                    + ", saveError=" + saveError
                    + ", showPasswordDialog=" + showPasswordDialog
                    + ", oldPassword=<redacted>"
                    + ", newPassword=<redacted>"
                    + ", confirmPassword=<redacted>"
                    + ", oldPasswordError=" + oldPasswordError
                    + ", newPasswordError=" + newPasswordError
                    + ", confirmMismatch=" + confirmMismatch
                    + ", passwordSaving=" + passwordSaving
                    + ", passwordError=" + passwordError
                    + ", passwordChanged=" + passwordChanged
                    + ", avatarUploading=" + avatarUploading
                    + ", avatarError=" + avatarError
                    + ", showPhoneDialog=" + showPhoneDialog
                    + ", phoneInput=" + phoneInput
                    + ", phonePassword=<redacted>"
                    + ", phoneInvalid=" + phoneInvalid
                    + ", phonePasswordMissing=" + phonePasswordMissing
                    + ", phoneSaving=" + phoneSaving
                    + ", phoneError=" + phoneError + ")";
        }

        private static final class Builder {
            private boolean loading = true;
            @Nullable private UserDto user;
            @Nullable private ApiError error;
            private boolean editingName;
            private String nameInput = "";
            private boolean saving;
            @Nullable private ApiError saveError;
            private boolean showPasswordDialog;
            private String oldPassword = "";
            private String newPassword = "";
            private String confirmPassword = "";
            private boolean oldPasswordError;
            private boolean newPasswordError;
            private boolean confirmMismatch;
            private boolean passwordSaving;
            @Nullable private ApiError passwordError;
            private boolean passwordChanged;
            private boolean avatarUploading;
            @Nullable private ApiError avatarError;
            private boolean showPhoneDialog;
            private String phoneInput = "";
            private String phonePassword = "";
            private boolean phoneInvalid;
            private boolean phonePasswordMissing;
            private boolean phoneSaving;
            @Nullable private ApiError phoneError;

            private Builder() {}

            private Builder(UiState source) {
                loading = source.loading;
                user = source.user;
                error = source.error;
                editingName = source.editingName;
                nameInput = source.nameInput;
                saving = source.saving;
                saveError = source.saveError;
                showPasswordDialog = source.showPasswordDialog;
                oldPassword = source.oldPassword;
                newPassword = source.newPassword;
                confirmPassword = source.confirmPassword;
                oldPasswordError = source.oldPasswordError;
                newPasswordError = source.newPasswordError;
                confirmMismatch = source.confirmMismatch;
                passwordSaving = source.passwordSaving;
                passwordError = source.passwordError;
                passwordChanged = source.passwordChanged;
                avatarUploading = source.avatarUploading;
                avatarError = source.avatarError;
                showPhoneDialog = source.showPhoneDialog;
                phoneInput = source.phoneInput;
                phonePassword = source.phonePassword;
                phoneInvalid = source.phoneInvalid;
                phonePasswordMissing = source.phonePasswordMissing;
                phoneSaving = source.phoneSaving;
                phoneError = source.phoneError;
            }
        }
    }
}
