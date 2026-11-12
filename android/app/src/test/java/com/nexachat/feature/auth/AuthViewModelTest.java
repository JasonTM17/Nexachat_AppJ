package com.nexachat.feature.auth;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.Observer;
import androidx.lifecycle.SavedStateHandle;
import com.nexachat.core.auth.FakeGoogleSignInClient;
import com.nexachat.core.auth.GoogleSignInException;
import com.nexachat.core.auth.GoogleSignInFlow;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.dto.AuthResponse;
import com.nexachat.core.network.dto.GoogleNonceResponse;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.data.AuthRepository;
import com.nexachat.data.AuthSessionResult;
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

    @Test
    public void uiStateStringDoesNotExposePassword() {
        AuthViewModel.UiState state = new AuthViewModel.UiState(
                "+84912345678", "auth-secret-marker", "Minh", false, null,
                false, false, false);

        assertFalse(state.toString().contains("auth-secret-marker"));
    }

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> Schedulers.trampoline());
        repository = mock(AuthRepository.class);
        viewModel = new AuthViewModel(repository,
                new GoogleSignInFlow(repository, new FakeGoogleSignInClient(true, "id-token")),
                new SavedStateHandle());
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

    @Test
    public void googleSignInSuccessPersistsTheSessionAndEmitsLoggedIn() {
        viewModel = googleViewModel(new FakeGoogleSignInClient(true, "google-id-token"));
        when(repository.requestGoogleNonce()).thenReturn(
                Single.just(new Result.Success<>(new GoogleNonceResponse("nonce-1", 300))));
        when(repository.googleSignIn("google-id-token", "nonce-1")).thenReturn(
                Single.just(new Result.Success<>(AuthSessionResult.existing(authResponse))));
        List<AuthViewModel.Event> events = observeEvents();

        viewModel.googleSignIn(null);

        assertEquals(List.of(AuthViewModel.Event.LoggedIn), events);
        assertFalse(viewModel.getUiState().getValue().getLoading());
        assertEquals(null, viewModel.getUiState().getValue().getError());
    }

    @Test
    public void cancellingTheGoogleSheetShowsNoError() {
        viewModel = googleViewModel(new FakeGoogleSignInClient(true,
                new GoogleSignInException(GoogleSignInException.Reason.CANCELLED)));
        when(repository.requestGoogleNonce()).thenReturn(
                Single.just(new Result.Success<>(new GoogleNonceResponse("nonce-1", 300))));
        List<AuthViewModel.Event> events = observeEvents();

        viewModel.googleSignIn(null);

        assertTrue(events.isEmpty());
        assertFalse(viewModel.getUiState().getValue().getLoading());
        assertEquals(null, viewModel.getUiState().getValue().getError());
    }

    @Test
    public void anUnlinkedGoogleAccountKeepsItsServerCodeInTheError() {
        viewModel = googleViewModel(new FakeGoogleSignInClient(true, "google-id-token"));
        when(repository.requestGoogleNonce()).thenReturn(
                Single.just(new Result.Success<>(new GoogleNonceResponse("nonce-1", 300))));
        when(repository.googleSignIn("google-id-token", "nonce-1")).thenReturn(
                Single.just(Result.failure(new ApiError.Unknown("GOOGLE_ACCOUNT_NOT_LINKED", 404))));
        List<AuthViewModel.Event> events = observeEvents();

        viewModel.googleSignIn(null);

        assertTrue(events.isEmpty());
        assertEquals("GOOGLE_ACCOUNT_NOT_LINKED",
                ((ApiError.Unknown) viewModel.getUiState().getValue().getError()).getCode());
    }

    @Test
    public void anUnconfiguredBuildHidesAndSkipsGoogleSignIn() {
        viewModel = googleViewModel(new FakeGoogleSignInClient(false, "google-id-token"));

        assertFalse(viewModel.isGoogleSignInAvailable());
        viewModel.googleSignIn(null);

        assertFalse(viewModel.getUiState().getValue().getLoading());
        verify(repository, never()).requestGoogleNonce();
    }

    @Test
    public void googleSignInNewAccountEmitsCompleteProfileOnce() {
        AuthResponse created = new AuthResponse(
                new UserDto("u-2", null, "New User", null, "user@gmail.com", true),
                "access-token",
                900,
                "refresh-token",
                86_400L,
                false,
                true);
        viewModel = googleViewModel(new FakeGoogleSignInClient(true, "google-id-token"));
        when(repository.requestGoogleNonce()).thenReturn(
                Single.just(new Result.Success<>(new GoogleNonceResponse("nonce-1", 300))));
        when(repository.googleSignIn("google-id-token", "nonce-1")).thenReturn(
                Single.just(new Result.Success<>(AuthSessionResult.newAccount(created))));
        List<AuthViewModel.Event> events = observeEvents();

        viewModel.googleSignIn(null);

        assertEquals(List.of(AuthViewModel.Event.CompleteProfile), events);
        assertTrue(viewModel.hasPendingCompleteProfile());
        viewModel.onCompleteProfileNavigationHandled();
        assertFalse(viewModel.hasPendingCompleteProfile());
        List<AuthViewModel.Event> afterRotation = observeEvents();
        assertTrue(afterRotation.isEmpty());
    }

    @Test
    public void googleSignInEmailInUseSurfacesInlineErrorWithoutNavigation() {
        viewModel = googleViewModel(new FakeGoogleSignInClient(true, "google-id-token"));
        when(repository.requestGoogleNonce()).thenReturn(
                Single.just(new Result.Success<>(new GoogleNonceResponse("nonce-1", 300))));
        when(repository.googleSignIn("google-id-token", "nonce-1")).thenReturn(
                Single.just(Result.failure(
                        new ApiError.Unknown(GoogleAuthErrors.GOOGLE_EMAIL_IN_USE, 409))));
        List<AuthViewModel.Event> events = observeEvents();

        viewModel.googleSignIn(null);

        assertTrue(events.isEmpty());
        assertEquals(GoogleAuthErrors.GOOGLE_EMAIL_IN_USE,
                ((ApiError.Unknown) viewModel.getUiState().getValue().getError()).getCode());
    }

    @Test
    public void googleSignInNetworkErrorShowsBannerWithoutNavigation() {
        viewModel = googleViewModel(new FakeGoogleSignInClient(true, "google-id-token"));
        when(repository.requestGoogleNonce()).thenReturn(
                Single.just(new Result.Success<>(new GoogleNonceResponse("nonce-1", 300))));
        when(repository.googleSignIn("google-id-token", "nonce-1")).thenReturn(
                Single.just(Result.failure(new ApiError.Network("offline"))));
        List<AuthViewModel.Event> events = observeEvents();

        viewModel.googleSignIn(null);

        assertTrue(events.isEmpty());
        assertTrue(viewModel.getUiState().getValue().getError() instanceof ApiError.Network);
    }

    private AuthViewModel googleViewModel(FakeGoogleSignInClient client) {
        return new AuthViewModel(repository, new GoogleSignInFlow(repository, client),
                new SavedStateHandle());
    }

    private List<AuthViewModel.Event> observeEvents() {
        List<AuthViewModel.Event> events = new ArrayList<>();
        Observer<AuthViewModel.Event> observer = events::add;
        viewModel.getEvents().observeForever(observer);
        return events;
    }
}
