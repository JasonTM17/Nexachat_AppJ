package com.nexachat.feature.profile;

import android.net.Uri;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.data.AuthRepository;
import com.nexachat.data.ContactsRepository;
import com.nexachat.data.SettingsRepository;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.SingleSubject;
import com.nexachat.core.common.Unit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public final class ProfileViewModelTest {

    @Rule public final InstantTaskExecutorRule taskExecutorRule = new InstantTaskExecutorRule();

    private ContactsRepository contactsRepository;
    private AuthRepository authRepository;
    private SettingsRepository settingsRepository;
    private Flowable<Boolean> showPresenceFlow;
    private final UserDto me = new UserDto("me-1", "+84900000000", "Son", null);

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> Schedulers.trampoline());
        contactsRepository = mock(ContactsRepository.class);
        authRepository = mock(AuthRepository.class);
        settingsRepository = mock(SettingsRepository.class);
        showPresenceFlow = Flowable.just(true);
        when(settingsRepository.getShowPresence()).thenReturn(showPresenceFlow);
        when(settingsRepository.getShowSeen()).thenReturn(Flowable.just(true));
    }

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
    }

    private ProfileViewModel createViewModel(Result<UserDto> meResult) {
        when(contactsRepository.getMe()).thenReturn(Single.just(meResult));
        return new ProfileViewModel(contactsRepository, authRepository, settingsRepository);
    }

    @Test
    public void initLoadsOwnUserRecord() {
        ProfileViewModel viewModel = createViewModel(new Result.Success<>(me));

        ProfileViewModel.UiState state = viewModel.getUiState().getValue();
        assertEquals(me, state.getUser());
        assertFalse(state.isLoading());
        assertNull(state.getError());
    }

    @Test
    public void loadFailureSurfacesErrorAndReloadRetries() {
        ProfileViewModel viewModel = createViewModel(
                Result.failure(new ApiError.Network("down")));
        assertEquals(new ApiError.Network("down"), viewModel.getUiState().getValue().getError());
        assertNull(viewModel.getUiState().getValue().getUser());

        when(contactsRepository.getMe()).thenReturn(Single.just(new Result.Success<>(me)));
        viewModel.load();

        ProfileViewModel.UiState state = viewModel.getUiState().getValue();
        assertEquals(me, state.getUser());
        assertNull(state.getError());
        verify(contactsRepository, times(2)).getMe();
    }

    @Test
    public void startEditNamePrefillsFieldAndCancelClearsIt() {
        ProfileViewModel viewModel = createViewModel(new Result.Success<>(me));

        viewModel.startEditName();
        assertTrue(viewModel.getUiState().getValue().isEditingName());
        assertEquals("Son", viewModel.getUiState().getValue().getNameInput());

        viewModel.cancelEditName();
        assertFalse(viewModel.getUiState().getValue().isEditingName());
        assertEquals("", viewModel.getUiState().getValue().getNameInput());
    }

    @Test
    public void saveDisplayNamePostsTrimmedNameAndUpdatesUser() {
        UserDto renamed = me.withDisplayName("Tien Son");
        when(contactsRepository.updateDisplayName(anyString()))
                .thenReturn(Single.just(new Result.Success<>(renamed)));

        ProfileViewModel viewModel = createViewModel(new Result.Success<>(me));
        viewModel.startEditName();
        viewModel.onNameInputChange("  Tien Son  ");
        viewModel.saveDisplayName();

        ProfileViewModel.UiState state = viewModel.getUiState().getValue();
        assertEquals(renamed, state.getUser());
        assertFalse(state.isEditingName());
        assertFalse(state.isSaving());
        assertNull(state.getSaveError());
        verify(contactsRepository, times(1)).updateDisplayName("Tien Son");
    }

    @Test
    public void blankNameInputIsBlockedBeforeRepository() {
        ProfileViewModel viewModel = createViewModel(new Result.Success<>(me));
        viewModel.startEditName();
        viewModel.onNameInputChange("   ");
        viewModel.saveDisplayName();

        assertTrue(viewModel.getUiState().getValue().isEditingName());
        verify(contactsRepository, never()).updateDisplayName(anyString());
    }

    @Test
    public void saveFailureSurfacesSaveErrorAndKeepsEditorOpen() {
        when(contactsRepository.updateDisplayName(anyString())).thenReturn(Single.just(
                Result.failure(new ApiError.Network("down"))));

        ProfileViewModel viewModel = createViewModel(new Result.Success<>(me));
        viewModel.startEditName();
        viewModel.onNameInputChange("New name");
        viewModel.saveDisplayName();

        ProfileViewModel.UiState state = viewModel.getUiState().getValue();
        assertEquals(new ApiError.Network("down"), state.getSaveError());
        assertTrue(state.isEditingName());
        assertFalse(state.isSaving());
    }

    @Test
    public void logoutDelegatesToAuthRepository() {
        when(authRepository.logout()).thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));

        ProfileViewModel viewModel = createViewModel(new Result.Success<>(me));
        viewModel.logout();

        verify(authRepository, times(1)).logout();
    }

    @Test
    public void logoutAllDelegatesToAuthRepository() {
        when(authRepository.logoutAll()).thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));

        ProfileViewModel viewModel = createViewModel(new Result.Success<>(me));
        viewModel.logoutAll();

        verify(authRepository, times(1)).logoutAll();
    }

    @Test
    public void passwordChangeSuccessClosesDialogAndRaisesPasswordChanged() {
        when(authRepository.changePassword(anyString(), anyString()))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));

        ProfileViewModel viewModel = createViewModel(new Result.Success<>(me));
        openAndFillPassword(viewModel, "oldpass123", "newpass456", "newpass456");
        viewModel.submitPasswordChange();

        ProfileViewModel.UiState state = viewModel.getUiState().getValue();
        assertFalse(state.isShowPasswordDialog());
        assertTrue(state.isPasswordChanged());
        assertFalse(state.isPasswordSaving());
        assertNull(state.getPasswordError());
        verify(authRepository, times(1)).changePassword("oldpass123", "newpass456");
    }

    @Test
    public void mismatchedConfirmIsBlockedLocallyBeforeRepository() {
        ProfileViewModel viewModel = createViewModel(new Result.Success<>(me));
        openAndFillPassword(viewModel, "oldpass123", "newpass456", "different1");
        viewModel.submitPasswordChange();

        ProfileViewModel.UiState state = viewModel.getUiState().getValue();
        assertTrue(state.isConfirmMismatch());
        assertTrue(state.isShowPasswordDialog());
        verify(authRepository, never()).changePassword(anyString(), anyString());
    }

    @Test
    public void shortNewPasswordAndEmptyOldAreFlaggedPerField() {
        ProfileViewModel viewModel = createViewModel(new Result.Success<>(me));
        openAndFillPassword(viewModel, "", "short", "short");
        viewModel.submitPasswordChange();

        ProfileViewModel.UiState state = viewModel.getUiState().getValue();
        assertTrue(state.isOldPasswordError());
        assertTrue(state.isNewPasswordError());
        verify(authRepository, never()).changePassword(anyString(), anyString());
    }

    @Test
    public void serverFailureKeepsPasswordDialogOpenWithError() {
        when(authRepository.changePassword(anyString(), anyString())).thenReturn(Single.just(
                Result.failure(ApiError.InvalidCredentials.INSTANCE)));

        ProfileViewModel viewModel = createViewModel(new Result.Success<>(me));
        openAndFillPassword(viewModel, "oldpass123", "newpass456", "newpass456");
        viewModel.submitPasswordChange();

        ProfileViewModel.UiState state = viewModel.getUiState().getValue();
        assertTrue(state.isShowPasswordDialog());
        assertEquals(ApiError.InvalidCredentials.INSTANCE, state.getPasswordError());
        assertFalse(state.isPasswordSaving());
    }

    @Test
    public void avatarUploadSuccessRefreshesUserRecordFromRepository() {
        Uri uri = mock(Uri.class);
        UserDto withAvatar = me.withAvatarUrl("/api/v1/files/avatars/me-1.png");
        when(contactsRepository.uploadAvatar(uri))
                .thenReturn(Single.just(new Result.Success<>(withAvatar)));

        ProfileViewModel viewModel = createViewModel(new Result.Success<>(me));
        viewModel.uploadAvatar(uri);

        ProfileViewModel.UiState state = viewModel.getUiState().getValue();
        assertEquals(withAvatar, state.getUser());
        assertEquals("/api/v1/files/avatars/me-1.png", state.getUser().getAvatarUrl());
        assertFalse(state.isAvatarUploading());
        assertNull(state.getAvatarError());
    }

    @Test
    public void avatarUploadFailureSurfacesErrorAndKeepsOldRecord() {
        Uri uri = mock(Uri.class);
        when(contactsRepository.uploadAvatar(uri)).thenReturn(Single.just(
                Result.failure(new ApiError.Network("avatar read failed"))));

        ProfileViewModel viewModel = createViewModel(new Result.Success<>(me));
        viewModel.uploadAvatar(uri);

        ProfileViewModel.UiState state = viewModel.getUiState().getValue();
        assertEquals(me, state.getUser());
        assertEquals(new ApiError.Network("avatar read failed"), state.getAvatarError());
        assertFalse(state.isAvatarUploading());
    }

    @Test
    public void secondPickWhileUploadingIsIgnored() {
        Uri uri = mock(Uri.class);
        Uri other = mock(Uri.class);
        UserDto withAvatar = me.withAvatarUrl("/api/v1/files/avatars/me-1.png");
        SingleSubject<Result<UserDto>> pending = SingleSubject.create();
        when(contactsRepository.uploadAvatar(any(Uri.class))).thenReturn(pending);

        ProfileViewModel viewModel = createViewModel(new Result.Success<>(me));
        viewModel.uploadAvatar(uri);
        viewModel.uploadAvatar(other);
        pending.onSuccess(new Result.Success<>(withAvatar));

        verify(contactsRepository, never()).uploadAvatar(other);
        assertEquals(withAvatar, viewModel.getUiState().getValue().getUser());
    }

    @Test
    public void presenceToggleDelegatesToSettingsRepository() {
        when(settingsRepository.setShowPresence(anyBoolean()))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        ProfileViewModel viewModel = createViewModel(new Result.Success<>(me));

        viewModel.setShowPresence(false);

        verify(settingsRepository, times(1)).setShowPresence(false);
        assertNull(viewModel.getUiState().getValue().getSaveError());
    }

    @Test
    public void rejectedPresenceToggleSurfacesError() {
        when(settingsRepository.setShowPresence(anyBoolean())).thenReturn(Single.just(
                Result.failure(ApiError.RateLimited.INSTANCE)));
        ProfileViewModel viewModel = createViewModel(new Result.Success<>(me));

        viewModel.setShowPresence(false);

        assertEquals(ApiError.RateLimited.INSTANCE, viewModel.getUiState().getValue().getSaveError());
    }

    @Test
    public void seenToggleDelegatesToSettingsRepository() {
        when(settingsRepository.setShowSeen(anyBoolean()))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        ProfileViewModel viewModel = createViewModel(new Result.Success<>(me));

        viewModel.setShowSeen(false);

        verify(settingsRepository, times(1)).setShowSeen(false);
        assertNull(viewModel.getUiState().getValue().getSaveError());
    }

    @Test
    public void rejectedSeenToggleSurfacesError() {
        when(settingsRepository.setShowSeen(anyBoolean())).thenReturn(Single.just(
                Result.failure(ApiError.RateLimited.INSTANCE)));
        ProfileViewModel viewModel = createViewModel(new Result.Success<>(me));

        viewModel.setShowSeen(false);

        assertEquals(ApiError.RateLimited.INSTANCE, viewModel.getUiState().getValue().getSaveError());
    }

    private static void openAndFillPassword(
            ProfileViewModel viewModel, String old, String password, String confirm) {
        viewModel.openPasswordDialog();
        viewModel.onOldPasswordChange(old);
        viewModel.onNewPasswordChange(password);
        viewModel.onConfirmPasswordChange(confirm);
    }
}
