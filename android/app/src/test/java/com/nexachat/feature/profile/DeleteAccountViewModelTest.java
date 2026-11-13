package com.nexachat.feature.profile;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.common.Unit;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.data.AuthRepository;
import com.nexachat.data.ContactsRepository;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.subjects.PublishSubject;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public final class DeleteAccountViewModelTest {

    @Rule public final InstantTaskExecutorRule taskExecutorRule = new InstantTaskExecutorRule();

    private AuthRepository authRepository;
    private ContactsRepository contactsRepository;
    private AccountDeletionCoordinator coordinator;

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> Schedulers.trampoline());
        authRepository = mock(AuthRepository.class);
        contactsRepository = mock(ContactsRepository.class);
        coordinator = new AccountDeletionCoordinator();
    }

    private DeleteAccountViewModel viewModel() {
        return new DeleteAccountViewModel(authRepository, contactsRepository, coordinator, true);
    }

    @Test
    public void repeatedSendIsBoundedByCooldownAndNeverDeletesAutomatically() {
        when(authRepository.requestAccountDeletionCode())
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        DeleteAccountViewModel vm = viewModel();
        vm.bindProfile(new UserDto("u1", null, "Demo", null, "a@b.c", true, false));
        vm.sendDeletionCode();
        vm.sendDeletionCode();
        verify(authRepository, times(1)).requestAccountDeletionCode();
        verify(authRepository, never()).deleteAccountWithCode(anyString());
        vm.onCleared();
    }

    @Test
    public void requestedCodeStillShowsResendControl() throws Exception {
        when(authRepository.requestAccountDeletionCode())
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        DeleteAccountViewModel vm = viewModel();
        vm.bindProfile(new UserDto("u1", null, "Demo", null, "a@b.c", true, false));
        vm.sendDeletionCode();
        com.nexachat.databinding.FragmentDeleteAccountBinding binding = render(vm);
        verify(binding.sendDeletionCode).setVisibility(android.view.View.VISIBLE);
        vm.onCleared();
    }

    private com.nexachat.databinding.FragmentDeleteAccountBinding render(DeleteAccountViewModel vm)
            throws Exception {
        var binding = mock(com.nexachat.databinding.FragmentDeleteAccountBinding.class);
        for (var field : binding.getClass().getFields()) {
            if (android.view.View.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                field.set(binding, mock(field.getType(), org.mockito.Mockito.RETURNS_DEEP_STUBS));
            }
        }
        DeleteAccountFragment fragment = mock(DeleteAccountFragment.class);
        var bindingField = DeleteAccountFragment.class.getDeclaredField("binding");
        bindingField.setAccessible(true);
        bindingField.set(fragment, binding);
        var render = DeleteAccountFragment.class.getDeclaredMethod("render", DeleteAccountViewModel.UiState.class);
        render.setAccessible(true);
        render.invoke(fragment, vm.getUiState().getValue());
        return binding;
    }

    private DeleteAccountViewModel codeViewModel(io.reactivex.rxjava3.schedulers.TestScheduler clock) {
        DeleteAccountViewModel vm = new DeleteAccountViewModel(authRepository, contactsRepository,
                coordinator, true, clock);
        vm.bindProfile(new UserDto("u1", null, "Demo", null, "a@b.c", true, false));
        return vm;
    }

    @Test public void wrongOrExpiredCodeCanBeResentAfterCooldownWithoutAutoSubmit() throws Exception {
        var clock = new io.reactivex.rxjava3.schedulers.TestScheduler();
        when(authRepository.requestAccountDeletionCode())
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        when(authRepository.deleteAccountWithCode("123456"))
                .thenReturn(Single.just(Result.failure(ApiError.InvalidOrExpiredCode.INSTANCE)));
        DeleteAccountViewModel vm = codeViewModel(clock);
        vm.sendDeletionCode();
        assertEquals(60, vm.getUiState().getValue().getResendSecondsRemaining());
        vm.onCodeChange("123456");
        vm.submit();
        assertTrue(vm.getUiState().getValue().isCodeInputError());
        clock.advanceTimeBy(59, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(1, vm.getUiState().getValue().getResendSecondsRemaining());
        vm.sendDeletionCode();
        verify(authRepository, times(1)).requestAccountDeletionCode();
        clock.advanceTimeBy(1, java.util.concurrent.TimeUnit.SECONDS);
        var binding = render(vm);
        verify(binding.sendDeletionCode).setVisibility(android.view.View.VISIBLE);
        verify(binding.sendDeletionCode).setEnabled(true);
        verify(binding.sendDeletionCode).setText(com.nexachat.R.string.recovery_email_resend);
        vm.sendDeletionCode();
        verify(authRepository, times(2)).requestAccountDeletionCode();
        verify(authRepository, times(1)).deleteAccountWithCode("123456");
        assertFalse(vm.getUiState().getValue().isCodeInputError());
        assertEquals("", vm.getUiState().getValue().getCode());
        assertEquals(60, vm.getUiState().getValue().getResendSecondsRemaining());
        vm.onCleared();
    }

    @Test public void rotationRetainsPendingResendGuardAndSubmitIsBlocked() throws Exception {
        var clock = new io.reactivex.rxjava3.schedulers.TestScheduler();
        var pending = io.reactivex.rxjava3.subjects.SingleSubject.<Result<Unit>>create();
        when(authRepository.requestAccountDeletionCode())
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)))
                .thenReturn(pending);
        DeleteAccountViewModel vm = codeViewModel(clock);
        vm.sendDeletionCode();
        clock.advanceTimeBy(60, java.util.concurrent.TimeUnit.SECONDS);
        vm.onCodeChange("123456");
        vm.sendDeletionCode();
        // A recreated view renders the retained ViewModel while its request is pending.
        var recreated = render(vm);
        verify(recreated.sendDeletionCode).setEnabled(false);
        vm.sendDeletionCode();
        vm.submit();
        verify(authRepository, times(2)).requestAccountDeletionCode();
        verify(authRepository, never()).deleteAccountWithCode(anyString());
        pending.onSuccess(new Result.Success<>(Unit.INSTANCE));
        assertFalse(vm.getUiState().getValue().isSendingCode());
        assertEquals("", vm.getUiState().getValue().getCode());
        vm.onCleared();
    }

    @Test public void failedResendCanRetryAndDoesNotResetCooldownOrHideControl() throws Exception {
        var clock = new io.reactivex.rxjava3.schedulers.TestScheduler();
        when(authRepository.requestAccountDeletionCode())
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)))
                .thenReturn(Single.just(Result.failure(ApiError.RateLimited.INSTANCE)))
                .thenReturn(Single.error(new java.io.IOException("offline")))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        DeleteAccountViewModel vm = codeViewModel(clock);
        vm.sendDeletionCode();
        clock.advanceTimeBy(60, java.util.concurrent.TimeUnit.SECONDS);
        vm.sendDeletionCode();
        assertEquals(ApiError.RateLimited.INSTANCE, vm.getUiState().getValue().getError());
        assertTrue(vm.getUiState().getValue().isCodeRequested());
        assertEquals(0, vm.getUiState().getValue().getResendSecondsRemaining());
        vm.sendDeletionCode();
        assertTrue(vm.getUiState().getValue().getError() instanceof ApiError.Network);
        vm.sendDeletionCode();
        verify(authRepository, times(4)).requestAccountDeletionCode();
        assertNull(vm.getUiState().getValue().getError());
        vm.onCleared();
    }

    @Test public void resendIsBlockedDuringDeletionAndTickerStopsWhenCleared() {
        var clock = new io.reactivex.rxjava3.schedulers.TestScheduler();
        when(authRepository.requestAccountDeletionCode())
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        when(authRepository.deleteAccountWithCode("123456")).thenReturn(Single.never());
        DeleteAccountViewModel vm = codeViewModel(clock);
        vm.sendDeletionCode();
        clock.advanceTimeBy(60, java.util.concurrent.TimeUnit.SECONDS);
        vm.onCodeChange("123456");
        vm.submit();
        vm.sendDeletionCode();
        verify(authRepository, times(1)).requestAccountDeletionCode();
        vm.onCleared();
        clock.advanceTimeBy(120, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(vm.getUiState().getValue().isDeleting());
    }

    private DeleteAccountViewModel viewModelWithProfileLoad() {
        return new DeleteAccountViewModel(authRepository, contactsRepository, coordinator, false);
    }

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
    }

    @Test
    public void beforeProfileLoadsCredentialModeStaysUnknown() {
        when(contactsRepository.getMe()).thenReturn(Single.never());
        DeleteAccountViewModel viewModel = viewModelWithProfileLoad();

        DeleteAccountViewModel.UiState state = viewModel.getUiState().getValue();
        assertFalse(state.isProfileLoaded());
        assertFalse(state.usesPasswordFlow());
        assertTrue(state.isProfileLoading());

        viewModel.submit();
        viewModel.sendDeletionCode();
        verify(authRepository, never()).deleteAccount(anyString());
        verify(authRepository, never()).deleteAccountWithCode(anyString());
        verify(authRepository, never()).requestAccountDeletionCode();
    }

    @Test
    public void failedProfileLoadKeepsCredentialUnknownWithError() {
        ApiError networkError = new ApiError.Network("offline");
        when(contactsRepository.getMe()).thenReturn(Single.just(Result.failure(networkError)));

        DeleteAccountViewModel viewModel = viewModelWithProfileLoad();
        DeleteAccountViewModel.UiState state = viewModel.getUiState().getValue();

        assertFalse(state.isProfileLoaded());
        assertFalse(state.isProfileLoading());
        assertFalse(state.usesPasswordFlow());
        assertEquals(networkError, state.getError());
        viewModel.submit();
        verify(authRepository, never()).deleteAccount(anyString());
        verify(authRepository, never()).deleteAccountWithCode(anyString());
    }

    @Test
    public void profileLoadErrorRetrySucceedsForPasswordlessAccount() {
        UserDto googleOnly =
                new UserDto("u1", null, "Demo", null, "a@b.c", true, false);
        ApiError networkError = new ApiError.Network("offline");
        when(contactsRepository.getMe())
                .thenReturn(Single.just(Result.failure(networkError)))
                .thenReturn(Single.just(new Result.Success<>(googleOnly)));

        DeleteAccountViewModel viewModel = viewModelWithProfileLoad();
        assertEquals(networkError, viewModel.getUiState().getValue().getError());

        viewModel.retryLoadProfile();

        DeleteAccountViewModel.UiState state = viewModel.getUiState().getValue();
        assertTrue(state.isProfileLoaded());
        assertFalse(state.usesPasswordFlow());
        assertNull(state.getError());
        verify(contactsRepository, times(2)).getMe();
    }

    @Test
    public void profileLoadErrorRetrySucceedsForPasswordAccount() {
        UserDto passwordUser =
                new UserDto("u1", "+1", "Demo", null, null, null, true);
        ApiError networkError = new ApiError.Network("offline");
        when(contactsRepository.getMe())
                .thenReturn(Single.just(Result.failure(networkError)))
                .thenReturn(Single.just(new Result.Success<>(passwordUser)));

        DeleteAccountViewModel viewModel = viewModelWithProfileLoad();
        viewModel.retryLoadProfile();

        DeleteAccountViewModel.UiState state = viewModel.getUiState().getValue();
        assertTrue(state.isProfileLoaded());
        assertTrue(state.usesPasswordFlow());
        assertNull(state.getError());
    }

    @Test
    public void profileLoadNetworkErrorKeepsCredentialUnknown() {
        when(contactsRepository.getMe()).thenReturn(Single.error(new RuntimeException("socket")));

        DeleteAccountViewModel viewModel = viewModelWithProfileLoad();
        DeleteAccountViewModel.UiState state = viewModel.getUiState().getValue();

        assertFalse(state.isProfileLoaded());
        assertFalse(state.usesPasswordFlow());
        assertTrue(state.getError() instanceof ApiError.Network);
    }

    @Test
    public void missingPasswordSetDefaultsToPasswordFlow() {
        DeleteAccountViewModel viewModel = viewModel();
        viewModel.bindProfile(new UserDto("u1", null, "Demo", null, null, null, null));

        assertTrue(viewModel.getUiState().getValue().usesPasswordFlow());
    }

    @Test
    public void passwordSetFalseSwitchesToCodeFlow() {
        DeleteAccountViewModel viewModel = viewModel();
        viewModel.bindProfile(new UserDto("u1", null, "Demo", null, "a@b.c", true, false));

        assertFalse(viewModel.getUiState().getValue().usesPasswordFlow());
    }

    @Test
    public void emptyPasswordIsBlockedBeforeRepository() {
        DeleteAccountViewModel viewModel = viewModel();
        viewModel.bindProfile(new UserDto("u1", "+1", "Demo", null, null, null, true));
        viewModel.submit();

        DeleteAccountViewModel.UiState state = viewModel.getUiState().getValue();
        assertTrue(state.isPasswordMissing());
        verify(authRepository, never()).deleteAccount(anyString());
    }

    @Test
    public void successKeepsDeletingUntilSessionEnds() {
        when(authRepository.deleteAccount("secret-pass"))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));

        DeleteAccountViewModel viewModel = viewModel();
        viewModel.bindProfile(new UserDto("u1", "+1", "Demo", null, null, null, true));
        viewModel.onPasswordChange("secret-pass");
        viewModel.submit();

        DeleteAccountViewModel.UiState state = viewModel.getUiState().getValue();
        assertTrue(state.isDeleting());
        assertNull(state.getError());
        assertEquals("", state.getPassword());
        verify(authRepository, times(1)).deleteAccount("secret-pass");
    }

    @Test
    public void wrongPasswordSurfacesInlineErrorAndReenablesForm() {
        when(authRepository.deleteAccount("bad"))
                .thenReturn(Single.just(Result.failure(ApiError.WrongPassword.INSTANCE)));

        DeleteAccountViewModel viewModel = viewModel();
        viewModel.bindProfile(new UserDto("u1", "+1", "Demo", null, null, null, true));
        viewModel.onPasswordChange("bad");
        viewModel.submit();

        DeleteAccountViewModel.UiState state = viewModel.getUiState().getValue();
        assertFalse(state.isDeleting());
        assertEquals(ApiError.WrongPassword.INSTANCE, state.getError());
    }

    @Test
    public void viewModelClearDoesNotCancelDeletionHeldByCoordinator() {
        PublishSubject<Result<Unit>> pending = PublishSubject.create();
        when(authRepository.deleteAccount("secret-pass")).thenReturn(pending.firstOrError());

        DeleteAccountViewModel viewModel = viewModel();
        viewModel.bindProfile(new UserDto("u1", "+1", "Demo", null, null, null, true));
        viewModel.onPasswordChange("secret-pass");
        viewModel.submit();
        viewModel.onCleared();
        pending.onNext(new Result.Success<>(Unit.INSTANCE));

        DeleteAccountViewModel.UiState state = viewModel.getUiState().getValue();
        assertTrue(state.isDeleting());
        assertNull(state.getError());
    }

    @Test
    public void rateLimitSurfacesInlineError() {
        when(authRepository.deleteAccount("secret-pass"))
                .thenReturn(Single.just(Result.failure(ApiError.RateLimited.INSTANCE)));

        DeleteAccountViewModel viewModel = viewModel();
        viewModel.bindProfile(new UserDto("u1", "+1", "Demo", null, null, null, true));
        viewModel.onPasswordChange("secret-pass");
        viewModel.submit();

        assertEquals(ApiError.RateLimited.INSTANCE, viewModel.getUiState().getValue().getError());
    }

    @Test
    public void secondSubmitWhileDeletingIsIgnored() {
        when(authRepository.deleteAccount("secret-pass"))
                .thenReturn(Single.never());

        DeleteAccountViewModel viewModel = viewModel();
        viewModel.bindProfile(new UserDto("u1", "+1", "Demo", null, null, null, true));
        viewModel.onPasswordChange("secret-pass");
        viewModel.submit();
        viewModel.submit();

        verify(authRepository, times(1)).deleteAccount("secret-pass");
    }

    @Test
    public void sendCodeSuccessOpensCodeStep() {
        when(authRepository.requestAccountDeletionCode())
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));

        DeleteAccountViewModel viewModel = viewModel();
        viewModel.bindProfile(new UserDto("u1", null, "Demo", null, "a@b.c", true, false));
        viewModel.sendDeletionCode();

        DeleteAccountViewModel.UiState state = viewModel.getUiState().getValue();
        assertTrue(state.isCodeRequested());
        assertFalse(state.isSendingCode());
        assertNull(state.getError());
    }

    @Test
    public void sendCodeRateLimitSurfacesInlineError() {
        when(authRepository.requestAccountDeletionCode())
                .thenReturn(Single.just(Result.failure(ApiError.RateLimited.INSTANCE)));

        DeleteAccountViewModel viewModel = viewModel();
        viewModel.bindProfile(new UserDto("u1", null, "Demo", null, "a@b.c", true, false));
        viewModel.sendDeletionCode();

        assertEquals(ApiError.RateLimited.INSTANCE, viewModel.getUiState().getValue().getError());
    }

    @Test
    public void emptyCodeIsBlockedBeforeRepository() {
        when(authRepository.requestAccountDeletionCode())
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));

        DeleteAccountViewModel viewModel = viewModel();
        viewModel.bindProfile(new UserDto("u1", null, "Demo", null, "a@b.c", true, false));
        viewModel.sendDeletionCode();
        viewModel.submit();

        assertTrue(viewModel.getUiState().getValue().isCodeMissing());
        verify(authRepository, never()).deleteAccountWithCode(anyString());
    }

    @Test
    public void wrongCodeSurfacesCodeInputError() {
        when(authRepository.deleteAccountWithCode("123456"))
                .thenReturn(Single.just(Result.failure(ApiError.InvalidOrExpiredCode.INSTANCE)));

        DeleteAccountViewModel viewModel = viewModel();
        viewModel.bindProfile(new UserDto("u1", null, "Demo", null, "a@b.c", true, false));
        when(authRepository.requestAccountDeletionCode())
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        viewModel.sendDeletionCode();
        viewModel.onCodeChange("123456");
        viewModel.submit();

        DeleteAccountViewModel.UiState state = viewModel.getUiState().getValue();
        assertFalse(state.isDeleting());
        assertTrue(state.isCodeInputError());
        assertEquals(ApiError.InvalidOrExpiredCode.INSTANCE, state.getError());
    }

    @Test
    public void codeDeletionSuccessKeepsDeletingUntilSessionEnds() {
        when(authRepository.requestAccountDeletionCode())
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        when(authRepository.deleteAccountWithCode("654321"))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));

        DeleteAccountViewModel viewModel = viewModel();
        viewModel.bindProfile(new UserDto("u1", null, "Demo", null, "a@b.c", true, false));
        viewModel.sendDeletionCode();
        viewModel.onCodeChange("654321");
        viewModel.submit();

        DeleteAccountViewModel.UiState state = viewModel.getUiState().getValue();
        assertTrue(state.isDeleting());
        assertNull(state.getError());
        verify(authRepository).deleteAccountWithCode("654321");
    }
}
