package com.nexachat.feature.auth;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.Observer;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.data.ContactsRepository;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public final class CompleteProfileViewModelTest {

    @Rule public final InstantTaskExecutorRule taskExecutorRule = new InstantTaskExecutorRule();

    private ContactsRepository contactsRepository;
    private final UserDto user = new UserDto(
            "u-1", null, "Nguyen Son", null, "son@gmail.com", true);

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> Schedulers.trampoline());
        contactsRepository = mock(ContactsRepository.class);
        when(contactsRepository.getMe()).thenReturn(Single.just(new Result.Success<>(user)));
    }

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
    }

    @Test
    public void unchangedNameSkipsPatchAndContinues() {
        CompleteProfileViewModel viewModel = new CompleteProfileViewModel(contactsRepository);
        List<CompleteProfileViewModel.Event> events = observeEvents(viewModel);
        viewModel.continueToInbox();

        assertEquals(List.of(CompleteProfileViewModel.Event.Continue), events);
        verify(contactsRepository, never()).updateDisplayName(anyString());
    }

    @Test
    public void changedValidNamePatchesThenContinues() {
        when(contactsRepository.updateDisplayName("Tien Son")).thenReturn(
                Single.just(new Result.Success<>(user.withDisplayName("Tien Son"))));
        CompleteProfileViewModel viewModel = new CompleteProfileViewModel(contactsRepository);
        List<CompleteProfileViewModel.Event> events = observeEvents(viewModel);
        viewModel.onDisplayNameChange("  Tien Son  ");
        viewModel.continueToInbox();

        assertEquals(List.of(CompleteProfileViewModel.Event.Continue), events);
        verify(contactsRepository, times(1)).updateDisplayName("Tien Son");
    }

    @Test
    public void blankNameShowsRequiredInlineError() {
        CompleteProfileViewModel viewModel = new CompleteProfileViewModel(contactsRepository);
        viewModel.onDisplayNameChange("   ");
        viewModel.continueToInbox();

        assertEquals(
                CompleteProfileDisplayNameRules.Problem.BLANK,
                viewModel.getUiState().getValue().getDisplayNameProblem());
        verify(contactsRepository, never()).updateDisplayName(anyString());
    }

    @Test
    public void tooLongNameShowsLengthInlineError() {
        CompleteProfileViewModel viewModel = new CompleteProfileViewModel(contactsRepository);
        viewModel.onDisplayNameChange("a".repeat(61));
        viewModel.continueToInbox();

        assertEquals(
                CompleteProfileDisplayNameRules.Problem.TOO_LONG,
                viewModel.getUiState().getValue().getDisplayNameProblem());
        verify(contactsRepository, never()).updateDisplayName(anyString());
    }

    @Test
    public void sixtyCharactersAfterTrimIsAccepted() {
        when(contactsRepository.updateDisplayName(anyString())).thenReturn(
                Single.just(new Result.Success<>(user)));
        CompleteProfileViewModel viewModel = new CompleteProfileViewModel(contactsRepository);
        viewModel.onDisplayNameChange(" " + "a".repeat(60) + " ");
        viewModel.continueToInbox();

        verify(contactsRepository, times(1)).updateDisplayName("a".repeat(60));
    }

    @Test
    public void loadFailureSurfacesError() {
        when(contactsRepository.getMe()).thenReturn(
                Single.just(Result.failure(new ApiError.Network("offline"))));
        CompleteProfileViewModel viewModel = new CompleteProfileViewModel(contactsRepository);

        assertFalse(viewModel.getUiState().getValue().getLoading());
        assertTrue(viewModel.getUiState().getValue().getError() instanceof ApiError.Network);
    }

    private static List<CompleteProfileViewModel.Event> observeEvents(
            CompleteProfileViewModel viewModel) {
        List<CompleteProfileViewModel.Event> events = new ArrayList<>();
        viewModel.getEvents().observeForever(events::add);
        return events;
    }
}
