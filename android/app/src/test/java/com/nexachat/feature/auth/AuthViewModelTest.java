package com.nexachat.feature.auth;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.Observer;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.dto.AuthResponse;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.data.AuthRepository;
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

public final class AuthViewModelTest {

    @Rule public final InstantTaskExecutorRule taskExecutorRule = new InstantTaskExecutorRule();

    private AuthRepository repository;
    private AuthViewModel viewModel;
    private final AuthResponse authResponse = new AuthResponse(
            new UserDto("u-1", "+84912345678", "Minh", null),
            "access-token",
            900,
            "refresh-token");

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> Schedulers.trampoline());
        repository = mock(AuthRepository.class);
        viewModel = new AuthViewModel(repository);
    }

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
    }

    @Test
    public void loginSuccessEmitsLoggedInAndClearsLoading() {
        when(repository.login(anyString(), anyString()))
                .thenReturn(Single.just(new Result.Success<>(authResponse)));
        List<AuthViewModel.Event> events = observeEvents();
        viewModel.onPhoneChange("0912345678");
        viewModel.onPasswordChange("secret123");

        viewModel.login();

        assertEquals(List.of(AuthViewModel.Event.LoggedIn), events);
        assertFalse(viewModel.getUiState().getValue().getLoading());
        assertEquals(null, viewModel.getUiState().getValue().getError());
        verify(repository, times(1)).login("0912345678", "secret123");

        List<AuthViewModel.Event> afterRotation = observeEvents();
        assertTrue(afterRotation.isEmpty());
    }

    @Test
    public void login401ShowsInvalidCredentialsAndEmitsNoEvent() {
        when(repository.login(anyString(), anyString()))
                .thenReturn(Single.just(Result.failure(ApiError.InvalidCredentials.INSTANCE)));
        List<AuthViewModel.Event> events = observeEvents();
        viewModel.onPhoneChange("0912345678");
        viewModel.onPasswordChange("wrong-pass");

        viewModel.login();

        assertTrue(events.isEmpty());
        assertEquals(ApiError.InvalidCredentials.INSTANCE, viewModel.getUiState().getValue().getError());
        assertFalse(viewModel.getUiState().getValue().getLoading());
    }

    @Test
    public void loginWithBlankFieldsIsBlockedBeforeRepository() {
        viewModel.login();

        AuthViewModel.UiState state = viewModel.getUiState().getValue();
        assertTrue(state.getPhoneError());
        assertTrue(state.getPasswordError());
        assertFalse(state.getLoading());
        verify(repository, never()).login(anyString(), anyString());
    }

    @Test
    public void loginWithShortPasswordIsBlocked() {
        viewModel.onPhoneChange("0912345678");
        viewModel.onPasswordChange("short");

        viewModel.login();

        assertTrue(viewModel.getUiState().getValue().getPasswordError());
        verify(repository, never()).login(anyString(), anyString());
    }

    @Test
    public void registerWithBlankDisplayNameIsBlocked() {
        viewModel.onPhoneChange("0912345678");
        viewModel.onPasswordChange("secret123");

        viewModel.register();

        assertTrue(viewModel.getUiState().getValue().getDisplayNameError());
        verify(repository, never()).register(anyString(), anyString(), anyString());
    }

    @Test
    public void registerSuccessEmitsLoggedIn() {
        when(repository.register(anyString(), anyString(), anyString()))
                .thenReturn(Single.just(new Result.Success<>(authResponse)));
        List<AuthViewModel.Event> events = observeEvents();
        viewModel.onDisplayNameChange("Minh");
        viewModel.onPhoneChange("0912345678");
        viewModel.onPasswordChange("secret123");

        viewModel.register();

        assertEquals(List.of(AuthViewModel.Event.LoggedIn), events);
        verify(repository, times(1)).register("0912345678", "secret123", "Minh");
    }

    @Test
    public void registerPhoneExistsMapsToPhoneExists() {
        when(repository.register(anyString(), anyString(), anyString()))
                .thenReturn(Single.just(Result.failure(ApiError.PhoneExists.INSTANCE)));
        viewModel.onDisplayNameChange("Minh");
        viewModel.onPhoneChange("0912345678");
        viewModel.onPasswordChange("secret123");

        viewModel.register();

        assertEquals(ApiError.PhoneExists.INSTANCE, viewModel.getUiState().getValue().getError());
    }

    private List<AuthViewModel.Event> observeEvents() {
        List<AuthViewModel.Event> events = new ArrayList<>();
        Observer<AuthViewModel.Event> observer = events::add;
        viewModel.getEvents().observeForever(observer);
        return events;
    }
}
