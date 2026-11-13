package com.nexachat.feature.profile;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.data.AuthRepository;
import com.nexachat.data.ContactsRepository;
import com.nexachat.data.SettingsRepository;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.junit.*;

public final class PasswordlessProfileActionsTest {
    @Rule public final InstantTaskExecutorRule executor = new InstantTaskExecutorRule();
    @Before public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
    }
    @After public void tearDown() { RxJavaPlugins.reset(); }

    @Test public void passwordlessAccountCannotOpenChangePassword() {
        ProfileViewModel vm = create(new UserDto("me", null, "User", null, "a@b.c", true, false));
        vm.openPasswordDialog();
        assertFalse(vm.getUiState().getValue().canChangePassword());
        assertFalse(vm.getUiState().getValue().isShowPasswordDialog());
    }

    @Test public void unloadedProfileCannotOpenChangePassword() {
        ProfileViewModel vm = create(null);
        vm.openPasswordDialog();
        assertFalse(vm.getUiState().getValue().canChangePassword());
        assertFalse(vm.getUiState().getValue().isShowPasswordDialog());
    }

    @Test public void passwordAccountCanOpenChangePassword() {
        ProfileViewModel vm = create(new UserDto("me", "+1", "User", null, null, null, true));
        vm.openPasswordDialog();
        assertTrue(vm.getUiState().getValue().canChangePassword());
        assertTrue(vm.getUiState().getValue().isShowPasswordDialog());
    }

    @Test public void absentPasswordFlagKeepsServerCompatibilityDefault() {
        ProfileViewModel vm = create(new UserDto("me", "+1", "User"));
        vm.openPasswordDialog();
        assertTrue(vm.getUiState().getValue().canChangePassword());
        assertTrue(vm.getUiState().getValue().isShowPasswordDialog());
    }

    private ProfileViewModel create(UserDto user) {
        ContactsRepository contacts = mock(ContactsRepository.class);
        when(contacts.getMe()).thenReturn(user == null ? Single.never()
                : Single.just(new Result.Success<>(user)));
        SettingsRepository settings = mock(SettingsRepository.class);
        when(settings.getShowPresence()).thenReturn(Flowable.just(true));
        when(settings.getShowSeen()).thenReturn(Flowable.just(true));
        return new ProfileViewModel(contacts, mock(AuthRepository.class), settings);
    }
}
