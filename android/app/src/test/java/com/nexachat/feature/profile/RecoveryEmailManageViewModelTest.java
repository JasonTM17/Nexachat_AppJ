package com.nexachat.feature.profile;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.data.ContactsRepository;
import com.nexachat.data.RecoveryEmailRepository;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class RecoveryEmailManageViewModelTest {

    @Rule public final InstantTaskExecutorRule task = new InstantTaskExecutorRule();

    private ContactsRepository contactsRepository;
    private RecoveryEmailRepository recoveryEmailRepository;

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
        contactsRepository = mock(ContactsRepository.class);
        recoveryEmailRepository = mock(RecoveryEmailRepository.class);
    }

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
    }

    @Test
    public void loadFailureLeavesUserNull() {
        when(contactsRepository.getMe())
                .thenReturn(Single.just(Result.failure(new ApiError.Network())));
        RecoveryEmailManageViewModel viewModel =
                new RecoveryEmailManageViewModel(contactsRepository, recoveryEmailRepository);
        RecoveryEmailManageViewModel.UiState state = viewModel.getUiState().getValue();
        assertNotNull(state);
        assertNull(state.getUser());
        assertFalse(state.isLoading());
        assertTrue(state.getError() instanceof ApiError.Network);
    }

    @Test
    public void loadSuccessStoresUser() {
        UserDto user = new UserDto("1", "+84900000000", "alice", null, "a@b.c", true);
        when(contactsRepository.getMe()).thenReturn(Single.just(new Result.Success<>(user)));
        RecoveryEmailManageViewModel viewModel =
                new RecoveryEmailManageViewModel(contactsRepository, recoveryEmailRepository);
        RecoveryEmailManageViewModel.UiState state = viewModel.getUiState().getValue();
        assertEquals(user, state.getUser());
        assertNull(state.getError());
    }

    @Test
    public void removeSuccessClearsVerifiedEmailAndSignalsCompletion() {
        UserDto user = new UserDto("1", "+84900000000", "alice", null, "a@b.c", true);
        when(contactsRepository.getMe()).thenReturn(Single.just(new Result.Success<>(user)));
        when(recoveryEmailRepository.removeVerifiedEmail())
                .thenReturn(Single.just(new Result.Success<Void>(null)));
        RecoveryEmailManageViewModel viewModel =
                new RecoveryEmailManageViewModel(contactsRepository, recoveryEmailRepository);

        viewModel.removeEmail();

        RecoveryEmailManageViewModel.UiState state = viewModel.getUiState().getValue();
        assertFalse(state.isRemoving());
        assertTrue(state.isRemoveComplete());
        assertNull(state.getUser().getEmail());
        assertFalse(state.getUser().hasVerifiedRecoveryEmail());

        viewModel.consumeRemoveComplete();
        assertFalse(viewModel.getUiState().getValue().isRemoveComplete());
    }

    @Test
    public void removeFailureKeepsTheVerifiedEmailAndShowsError() {
        UserDto user = new UserDto("1", "+84900000000", "alice", null, "a@b.c", true);
        when(contactsRepository.getMe()).thenReturn(Single.just(new Result.Success<>(user)));
        ApiError apiError = new ApiError.Network();
        when(recoveryEmailRepository.removeVerifiedEmail())
                .thenReturn(Single.just(Result.failure(apiError)));
        RecoveryEmailManageViewModel viewModel =
                new RecoveryEmailManageViewModel(contactsRepository, recoveryEmailRepository);

        viewModel.removeEmail();

        RecoveryEmailManageViewModel.UiState state = viewModel.getUiState().getValue();
        assertFalse(state.isRemoving());
        assertFalse(state.isRemoveComplete());
        assertEquals(apiError, state.getError());
        assertEquals("a@b.c", state.getUser().getEmail());
        assertTrue(state.getUser().hasVerifiedRecoveryEmail());
    }
}
