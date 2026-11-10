package com.nexachat.feature.profile;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.SavedStateHandle;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.common.Unit;
import io.reactivex.rxjava3.core.Single;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public final class RecoveryEmailFormViewModelTest {

    @Rule public final InstantTaskExecutorRule task = new InstantTaskExecutorRule();

    private com.nexachat.data.RecoveryEmailRepository repository;

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
        repository = mock(com.nexachat.data.RecoveryEmailRepository.class);
    }

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
    }

  @Test
  public void invalidEmailDoesNotCallApi() {
    RecoveryEmailFormViewModel viewModel =
            new RecoveryEmailFormViewModel(repository, new SavedStateHandle());
    viewModel.onEmailChanged("not-an-email");
    viewModel.sendCode();

    assertTrue(viewModel.getUiState().getValue().isEmailInvalid());
    verify(repository, never()).requestVerificationCode(anyString());
  }

    @Test
    public void sendSuccessMarksCodeSent() {
        when(repository.requestVerificationCode("user@test.com"))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        RecoveryEmailFormViewModel viewModel =
                new RecoveryEmailFormViewModel(repository, new SavedStateHandle());
        viewModel.onEmailChanged("user@test.com");
        viewModel.sendCode();

        RecoveryEmailFormViewModel.UiState state = viewModel.getUiState().getValue();
        assertTrue(state.isCodeSent());
        assertEquals("user@test.com", state.getPendingEmail());
        assertFalse(state.isSending());
    }

    @Test
    public void mailUnavailableSurfacesError() {
        when(repository.requestVerificationCode(anyString()))
                .thenReturn(Single.just(Result.failure(ApiError.MailUnavailable.INSTANCE)));
        RecoveryEmailFormViewModel viewModel =
                new RecoveryEmailFormViewModel(repository, new SavedStateHandle());
        viewModel.onEmailChanged("user@test.com");
        viewModel.sendCode();

        assertEquals(
                ApiError.MailUnavailable.INSTANCE,
                viewModel.getUiState().getValue().getError());
        assertFalse(viewModel.getUiState().getValue().isCodeSent());
    }

    @Test
    public void rotationRestoresEmailInputFromSavedState() {
        SavedStateHandle handle = new SavedStateHandle();
        handle.set("recovery_form_email", "kept@test.com");
        RecoveryEmailFormViewModel viewModel =
                new RecoveryEmailFormViewModel(repository, handle);

        assertEquals("kept@test.com", viewModel.getUiState().getValue().getEmailInput());
        assertNull(viewModel.getUiState().getValue().getError());
    }
}
