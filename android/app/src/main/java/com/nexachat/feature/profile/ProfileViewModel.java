package com.nexachat.feature.profile;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelKt;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.data.AuthRepository;
import com.nexachat.data.ContactsRepository;
import com.nexachat.data.SettingsRepository;
import dagger.hilt.android.lifecycle.HiltViewModel;
import java.util.Objects;
import java.util.function.Consumer;
import javax.inject.Inject;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineStart;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

/**
 * Profile screen state and actions. Logout is broadcast by AuthRepository and
 * handled by the AppNavHost-level collector, which clears the navigation stack.
 */
@HiltViewModel
public final class ProfileViewModel extends ViewModel {

    private final ContactsRepository contactsRepository;
    private final AuthRepository authRepository;
    private final SettingsRepository settingsRepository;
    private final MutableStateFlow<UiState> mutableUiState =
            StateFlowKt.MutableStateFlow(new UiState());
    private final StateFlow<UiState> uiState = mutableUiState;
    private final StateFlow<Boolean> showPresence;

    @Inject
    public ProfileViewModel(
            ContactsRepository contactsRepository,
            AuthRepository authRepository,
            SettingsRepository settingsRepository) {
        this.contactsRepository = Objects.requireNonNull(contactsRepository, "contactsRepository");
        this.authRepository = Objects.requireNonNull(authRepository, "authRepository");
        this.settingsRepository = Objects.requireNonNull(settingsRepository, "settingsRepository");
        showPresence = settingsRepository.getShowPresence();
        load();
    }

    public StateFlow<UiState> getUiState() {
        return uiState;
    }

    /** Presence privacy toggle (F3), backed by the settings repository. */
    public StateFlow<Boolean> getShowPresence() {
        return showPresence;
    }

    public void load() {
        launch((scope, continuation) ->
                ProfileViewModelSupportKt.profileLoad(
                        contactsRepository, mutableUiState, continuation));
    }

    public void startEditName() {
        UiState state = mutableUiState.getValue();
        UserDto user = state.getUser();
        String displayName = user == null ? "" : user.getDisplayName();
        mutableUiState.setValue(state
                .withEditingName(true)
                .withNameInput(displayName == null ? "" : displayName)
                .withSaveError(null));
    }

    public void onNameInputChange(String value) {
        Objects.requireNonNull(value, "value");
        mutableUiState.setValue(mutableUiState.getValue()
                .withNameInput(value)
                .withSaveError(null));
    }

    public void cancelEditName() {
        mutableUiState.setValue(mutableUiState.getValue()
                .withEditingName(false)
                .withNameInput("")
                .withSaveError(null));
    }

    public void saveDisplayName() {
        UiState state = mutableUiState.getValue();
        String name = trimWhitespace(state.getNameInput());
        if (name.isEmpty() || state.isSaving()) {
            return;
        }
        launch((scope, continuation) ->
                ProfileViewModelSupportKt.profileSaveDisplayName(
                        contactsRepository, mutableUiState, name, continuation));
    }

    public void openPasswordDialog() {
        mutableUiState.setValue(mutableUiState.getValue()
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
        mutableUiState.setValue(mutableUiState.getValue()
                .withShowPasswordDialog(false)
                .withPasswordError(null));
    }

    public void onOldPasswordChange(String value) {
        Objects.requireNonNull(value, "value");
        mutableUiState.setValue(mutableUiState.getValue()
                .withOldPassword(value)
                .withOldPasswordError(false)
                .withPasswordError(null));
    }

    public void onNewPasswordChange(String value) {
        Objects.requireNonNull(value, "value");
        mutableUiState.setValue(mutableUiState.getValue()
                .withNewPassword(value)
                .withNewPasswordError(false)
                .withPasswordError(null));
    }

    public void onConfirmPasswordChange(String value) {
        Objects.requireNonNull(value, "value");
        mutableUiState.setValue(mutableUiState.getValue()
                .withConfirmPassword(value)
                .withConfirmMismatch(false)
                .withPasswordError(null));
    }

    public void consumePasswordChanged() {
        mutableUiState.setValue(mutableUiState.getValue().withPasswordChanged(false));
    }

    public void submitPasswordChange() {
        UiState state = mutableUiState.getValue();
        if (state.isPasswordSaving()) {
            return;
        }
        String oldPassword = state.getOldPassword();
        String newPassword = state.getNewPassword();
        boolean oldBad = oldPassword.isEmpty();
        boolean newBad = newPassword.length() < 8;
        boolean mismatch = !newPassword.equals(state.getConfirmPassword());
        if (oldBad || newBad || mismatch) {
            mutableUiState.setValue(state
                    .withOldPasswordError(oldBad)
                    .withNewPasswordError(newBad)
                    .withConfirmMismatch(mismatch));
            return;
        }
        launch((scope, continuation) ->
                ProfileViewModelSupportKt.profileChangePassword(
                        authRepository,
                        mutableUiState,
                        oldPassword,
                        newPassword,
                        continuation));
    }

    /** Uploads the selected avatar and stores the server-refreshed user record. */
    public void uploadAvatar(Uri uri) {
        Objects.requireNonNull(uri, "uri");
        if (mutableUiState.getValue().isAvatarUploading()) {
            return;
        }
        launch((scope, continuation) ->
                ProfileViewModelSupportKt.profileUploadAvatar(
                        contactsRepository, mutableUiState, uri, continuation));
    }

    public void consumeAvatarError() {
        mutableUiState.setValue(mutableUiState.getValue().withAvatarError(null));
    }

    /** The repository applies the presence change optimistically and reverts on failure. */
    public void setShowPresence(boolean value) {
        launch((scope, continuation) ->
                ProfileViewModelSupportKt.profileSetShowPresence(
                        settingsRepository, mutableUiState, value, continuation));
    }

    /** Clears the local session and broadcasts LoggedOut. */
    public void logout() {
        launch((scope, continuation) ->
                ProfileViewModelSupportKt.profileLogout(authRepository, continuation));
    }

    /** Revokes every refresh token, then clears the local session. */
    public void logoutAll() {
        launch((scope, continuation) ->
                ProfileViewModelSupportKt.profileLogoutAll(authRepository, continuation));
    }

    private void launch(Function2<CoroutineScope, Continuation<? super Unit>, Object> block) {
        BuildersKt.launch(
                ViewModelKt.getViewModelScope(this),
                EmptyCoroutineContext.INSTANCE,
                CoroutineStart.DEFAULT,
                block);
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
        }

        private UiState change(Consumer<Builder> update) {
            Builder builder = new Builder(this);
            update.accept(builder);
            return new UiState(builder);
        }

        public boolean isLoading() { return loading; }
        public boolean getLoading() { return loading; }
        @Nullable public UserDto getUser() { return user; }
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
                    && Objects.equals(user, that.user)
                    && Objects.equals(error, that.error)
                    && nameInput.equals(that.nameInput)
                    && Objects.equals(saveError, that.saveError)
                    && oldPassword.equals(that.oldPassword)
                    && newPassword.equals(that.newPassword)
                    && confirmPassword.equals(that.confirmPassword)
                    && Objects.equals(passwordError, that.passwordError)
                    && Objects.equals(avatarError, that.avatarError);
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
                    avatarError);
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
                    + ", oldPassword=" + oldPassword
                    + ", newPassword=" + newPassword
                    + ", confirmPassword=" + confirmPassword
                    + ", oldPasswordError=" + oldPasswordError
                    + ", newPasswordError=" + newPasswordError
                    + ", confirmMismatch=" + confirmMismatch
                    + ", passwordSaving=" + passwordSaving
                    + ", passwordError=" + passwordError
                    + ", passwordChanged=" + passwordChanged
                    + ", avatarUploading=" + avatarUploading
                    + ", avatarError=" + avatarError + ")";
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
            }
        }
    }
}
