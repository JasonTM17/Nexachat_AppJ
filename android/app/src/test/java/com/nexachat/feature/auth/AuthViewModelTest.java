package com.nexachat.feature.auth;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.Observer;
import androidx.lifecycle.SavedStateHandle;
import com.nexachat.core.auth.FakeGoogleSignInClient;
import com.nexachat.core.auth.GoogleSignInException;
import com.nexachat.core.auth.GoogleSignInFlow;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.common.Unit;
import com.nexachat.core.network.dto.AuthResponse;
import com.nexachat.core.network.dto.GoogleNonceResponse;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.data.AuthRepository;
import com.nexachat.data.AuthSessionResult;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.SingleSubject;
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
                false, false, null);

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

        assertEquals(
                CompleteProfileDisplayNameRules.Problem.BLANK,
                viewModel.getUiState().getValue().getDisplayNameProblem());
        verify(repository, never()).register(anyString(), anyString(), anyString());
    }

    @Test
    public void registerWithWhitespaceOnlyDisplayNameShowsRequiredError() {
        viewModel.onPhoneChange("0912345678");
        viewModel.onPasswordChange("secret123");
        viewModel.onDisplayNameChange("   ");

        viewModel.register();

        assertEquals(
                CompleteProfileDisplayNameRules.Problem.BLANK,
                viewModel.getUiState().getValue().getDisplayNameProblem());
        verify(repository, never()).register(anyString(), anyString(), anyString());
    }

    @Test
    public void registerWithTooLongDisplayNameIsBlockedBeforeRepository() {
        viewModel.onPhoneChange("0912345678");
        viewModel.onPasswordChange("secret123");
        viewModel.onDisplayNameChange("a".repeat(61));

        viewModel.register();

        assertEquals(
                CompleteProfileDisplayNameRules.Problem.TOO_LONG,
                viewModel.getUiState().getValue().getDisplayNameProblem());
        verify(repository, never()).register(anyString(), anyString(), anyString());
    }

    @Test
    public void registerWithSixtyCharactersAfterTrimCallsRepositoryWithTrimmedName() {
        when(repository.register(anyString(), anyString(), anyString()))
                .thenReturn(Single.just(new Result.Success<>(authResponse)));
        String padded = " " + "a".repeat(60) + " ";
        viewModel.onPhoneChange("0912345678");
        viewModel.onPasswordChange("secret123");
        viewModel.onDisplayNameChange(padded);

        viewModel.register();

        verify(repository, times(1))
                .register("0912345678", "secret123", "a".repeat(60));
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

    // ------------------------------------------------------------ email sign-up

    @Test
    public void requestSignupCodeShowsTheCodeStep() {
        when(repository.requestEmailSignupCode(anyString()))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        fillSignupForm();

        viewModel.requestSignupCode();

        verify(repository).requestEmailSignupCode("minh@example.com");
        assertTrue(viewModel.getEmailSignup().getValue().isCodeSent());
        assertFalse(viewModel.getUiState().getValue().getLoading());
        assertEquals(null, viewModel.getUiState().getValue().getError());
    }

    @Test
    public void requestSignupCodeWithABadEmailIsBlockedBeforeTheRepository() {
        viewModel.onDisplayNameChange("Minh");
        viewModel.onEmailChange("not an email");
        viewModel.onPasswordChange("secret123");

        viewModel.requestSignupCode();

        assertTrue(viewModel.getEmailSignup().getValue().getEmailError());
        assertFalse(viewModel.getEmailSignup().getValue().isCodeSent());
        verify(repository, never()).requestEmailSignupCode(anyString());
    }

    @Test
    public void requestSignupCodeWithAShortPasswordIsBlocked() {
        viewModel.onDisplayNameChange("Minh");
        viewModel.onEmailChange("minh@example.com");
        viewModel.onPasswordChange("short");

        viewModel.requestSignupCode();

        assertTrue(viewModel.getUiState().getValue().getPasswordError());
        verify(repository, never()).requestEmailSignupCode(anyString());
    }

    @Test
    public void anEmailThatIsAlreadyRegisteredStaysOnTheFormWithTheError() {
        when(repository.requestEmailSignupCode(anyString()))
                .thenReturn(Single.just(Result.failure(ApiError.EmailAlreadyInUse.INSTANCE)));
        fillSignupForm();

        viewModel.requestSignupCode();

        assertFalse(viewModel.getEmailSignup().getValue().isCodeSent());
        assertEquals(ApiError.EmailAlreadyInUse.INSTANCE, viewModel.getUiState().getValue().getError());
    }

    @Test
    public void registerWithEmailSendsTheCodeAndEmitsLoggedIn() {
        when(repository.requestEmailSignupCode(anyString()))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        when(repository.registerWithEmail(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Single.just(new Result.Success<>(authResponse)));
        List<AuthViewModel.Event> events = observeEvents();
        fillSignupForm();
        viewModel.requestSignupCode();
        viewModel.onSignupCodeChange("12 34 56");

        viewModel.registerWithEmail();

        verify(repository).registerWithEmail("minh@example.com", "123456", "secret123", "Minh");
        assertEquals(List.of(AuthViewModel.Event.LoggedIn), events);
    }

    @Test
    public void anIncompleteCodeIsNotSubmitted() {
        when(repository.requestEmailSignupCode(anyString()))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        fillSignupForm();
        viewModel.requestSignupCode();
        viewModel.onSignupCodeChange("123");

        viewModel.registerWithEmail();

        assertTrue(viewModel.getEmailSignup().getValue().getCodeError());
        verify(repository, never())
                .registerWithEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    public void aWrongCodeMarksTheCodeFieldWithoutABannerOrNavigation() {
        when(repository.requestEmailSignupCode(anyString()))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        when(repository.registerWithEmail(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Single.just(Result.failure(ApiError.InvalidOrExpiredCode.INSTANCE)));
        List<AuthViewModel.Event> events = observeEvents();
        fillSignupForm();
        viewModel.requestSignupCode();
        viewModel.onSignupCodeChange("000000");

        viewModel.registerWithEmail();

        assertTrue(viewModel.getEmailSignup().getValue().getCodeError());
        assertEquals(null, viewModel.getUiState().getValue().getError());
        assertTrue(events.isEmpty());
    }

    @Test
    public void resendWaitsForTheCooldownThenSendsAgain() {
        MutableClock clock = new MutableClock(1_000_000L);
        AuthViewModel timed = new AuthViewModel(repository,
                new GoogleSignInFlow(repository, new FakeGoogleSignInClient(true, "id-token")),
                new SavedStateHandle(), clock);
        when(repository.requestEmailSignupCode(anyString()))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        timed.onDisplayNameChange("Minh");
        timed.onEmailChange("minh@example.com");
        timed.onPasswordChange("secret123");
        timed.requestSignupCode();
        assertEquals(60, timed.getEmailSignup().getValue().resendSecondsRemaining(clock.millis()));

        timed.resendSignupCode();
        verify(repository, times(1)).requestEmailSignupCode(anyString());

        clock.advance(60_000L);
        assertEquals(0, timed.getEmailSignup().getValue().resendSecondsRemaining(clock.millis()));
        timed.resendSignupCode();
        verify(repository, times(2)).requestEmailSignupCode(anyString());
    }

    @Test
    public void changingTheEmailReturnsToTheForm() {
        when(repository.requestEmailSignupCode(anyString()))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        fillSignupForm();
        viewModel.requestSignupCode();

        viewModel.changeSignupEmail();

        assertFalse(viewModel.getEmailSignup().getValue().isCodeSent());
        assertEquals("minh@example.com", viewModel.getEmailSignup().getValue().getEmail());
    }

    @Test
    public void aLateCodeRequestSuccessCannotReopenTheCodeStepAfterChangingTheEmail() {
        SingleSubject<Result<Unit>> codeRequest = SingleSubject.create();
        when(repository.requestEmailSignupCode(anyString())).thenReturn(codeRequest);
        fillSignupForm();
        viewModel.requestSignupCode();
        viewModel.changeSignupEmail();
        assertFalse(viewModel.getEmailSignup().getValue().isCodeSent());

        codeRequest.onSuccess(new Result.Success<>(Unit.INSTANCE));

        assertFalse(viewModel.getEmailSignup().getValue().isCodeSent());
        assertFalse(viewModel.getUiState().getValue().getLoading());
        assertEquals(null, viewModel.getUiState().getValue().getError());
    }

    @Test
    public void aLateCodeRequestFailureCannotSurfaceABannerAfterChangingTheEmail() {
        SingleSubject<Result<Unit>> codeRequest = SingleSubject.create();
        when(repository.requestEmailSignupCode(anyString())).thenReturn(codeRequest);
        fillSignupForm();
        viewModel.requestSignupCode();
        viewModel.changeSignupEmail();

        codeRequest.onSuccess(Result.failure(ApiError.RateLimited.INSTANCE));

        assertEquals(null, viewModel.getUiState().getValue().getError());
        assertFalse(viewModel.getUiState().getValue().getLoading());
    }

    @Test
    public void signupStateStringDoesNotExposeTheCode() {
        AuthViewModel.EmailSignupState state = new AuthViewModel.EmailSignupState()
                .withCodeSent(0L).withCode("918273");

        assertFalse(state.toString().contains("918273"));
    }

    private void fillSignupForm() {
        viewModel.onDisplayNameChange("Minh");
        viewModel.onEmailChange(" Minh@Example.com ".trim().toLowerCase());
        viewModel.onPasswordChange("secret123");
    }

    private static final class MutableClock extends java.time.Clock {
        private long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(long deltaMs) {
            millis += deltaMs;
        }

        @Override public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override public java.time.Instant instant() {
            return java.time.Instant.ofEpochMilli(millis);
        }

        @Override public long millis() {
            return millis;
        }
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
        assertTrue(viewModel.onCompleteProfileNavigationHandled());
        assertFalse(viewModel.hasPendingCompleteProfile());
        assertFalse(viewModel.onCompleteProfileNavigationHandled());
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
    public void googleSignInDisabledOnServerEndsLoadingWithNotAvailable() {
        viewModel = googleViewModel(new FakeGoogleSignInClient(true, "google-id-token"));
        when(repository.requestGoogleNonce()).thenReturn(
                Single.just(Result.failure(new ApiError.Unknown("NOT_FOUND", 404))));
        List<AuthViewModel.Event> events = observeEvents();

        viewModel.googleSignIn(null);

        assertTrue(events.isEmpty());
        assertFalse(viewModel.getUiState().getValue().getLoading());
        assertEquals(GoogleSignInFlow.CODE_NOT_AVAILABLE,
                ((ApiError.Unknown) viewModel.getUiState().getValue().getError()).getCode());
    }

    @Test
    public void googleSignInDeveloperErrorEndsLoading() {
        viewModel = googleViewModel(new FakeGoogleSignInClient(true,
                new GoogleSignInException(GoogleSignInException.Reason.DEVELOPER_ERROR)));
        when(repository.requestGoogleNonce()).thenReturn(
                Single.just(new Result.Success<>(new GoogleNonceResponse("nonce-1", 300))));
        List<AuthViewModel.Event> events = observeEvents();

        viewModel.googleSignIn(null);

        assertTrue(events.isEmpty());
        assertFalse(viewModel.getUiState().getValue().getLoading());
        assertEquals(GoogleSignInFlow.CODE_DEVELOPER_ERROR,
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
