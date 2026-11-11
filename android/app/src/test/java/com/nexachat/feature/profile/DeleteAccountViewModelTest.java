package com.nexachat.feature.profile;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.common.Unit;
import com.nexachat.data.AuthRepository;
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
    private AccountDeletionCoordinator coordinator;

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> Schedulers.trampoline());
        authRepository = mock(AuthRepository.class);
        coordinator = new AccountDeletionCoordinator();
    }

    private DeleteAccountViewModel viewModel() {
        return new DeleteAccountViewModel(authRepository, coordinator);
    }

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
    }

    @Test
    public void emptyPasswordIsBlockedBeforeRepository() {
        DeleteAccountViewModel viewModel = viewModel();
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
        viewModel.onPasswordChange("secret-pass");
        viewModel.submit();

        DeleteAccountViewModel.UiState state = viewModel.getUiState().getValue();
        assertTrue(state.isDeleting());
        assertNull(state.getError());
        verify(authRepository, times(1)).deleteAccount("secret-pass");
    }

    @Test
    public void wrongPasswordSurfacesInlineErrorAndReenablesForm() {
        when(authRepository.deleteAccount("bad"))
                .thenReturn(Single.just(Result.failure(ApiError.WrongPassword.INSTANCE)));

        DeleteAccountViewModel viewModel = viewModel();
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
        viewModel.onPasswordChange("secret-pass");
        viewModel.submit();

        assertEquals(ApiError.RateLimited.INSTANCE, viewModel.getUiState().getValue().getError());
    }

    @Test
    public void secondSubmitWhileDeletingIsIgnored() {
        when(authRepository.deleteAccount("secret-pass"))
                .thenReturn(Single.never());

        DeleteAccountViewModel viewModel = viewModel();
        viewModel.onPasswordChange("secret-pass");
        viewModel.submit();
        viewModel.submit();

        verify(authRepository, times(1)).deleteAccount("secret-pass");
    }
}
