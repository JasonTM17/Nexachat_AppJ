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
