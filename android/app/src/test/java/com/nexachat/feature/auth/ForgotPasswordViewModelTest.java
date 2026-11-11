package com.nexachat.feature.auth;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.SavedStateHandle;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.common.Unit;
import com.nexachat.data.PasswordResetRepository;
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public final class ForgotPasswordViewModelTest {

    @Rule public final InstantTaskExecutorRule taskRule = new InstantTaskExecutorRule();

    private PasswordResetRepository repository;
    private MutableClock clock;
    private TestScheduler tickerScheduler;
    private ForgotPasswordViewModel viewModel;

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> Schedulers.trampoline());
        RxJavaPlugins.setComputationSchedulerHandler(scheduler -> Schedulers.trampoline());
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        repository = mock(PasswordResetRepository.class);
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        tickerScheduler = new TestScheduler();
        SavedStateHandle handle = new SavedStateHandle();
        viewModel = new ForgotPasswordViewModel(repository, handle, clock, tickerScheduler);
    }

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
    }

    @Test
    public void sendCodeWithInvalidPhoneSetsValidationWithoutApiCall() {
        viewModel.onPhoneChanged("invalid");
        viewModel.sendCode();
        assertTrue(viewModel.getUiState().getValue().isPhoneInvalid());
        verify(repository, never()).requestResetCode(anyString());
    }

    @Test
    public void sendCodeAlwaysShowsNeutralStepOn202() {
        when(repository.requestResetCode(anyString()))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        viewModel.onPhoneChanged("+84912345678");
        viewModel.sendCode();
        ForgotPasswordViewModel.UiState state = viewModel.getUiState().getValue();
        assertTrue(state.isCodeRequested());
        assertEquals(ForgotPasswordViewModel.RESEND_COOLDOWN_SECONDS, state.getResendSecondsRemaining());
        verify(repository, times(1)).requestResetCode("+84912345678");
    }

    @Test
    public void submitResetEmitsEventOn204() {
        when(repository.requestResetCode(anyString()))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        when(repository.confirmReset(anyString(), anyString(), anyString()))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        List<ForgotPasswordViewModel.Event> events = observeEvents();
        viewModel.onPhoneChanged("+84912345678");
        viewModel.sendCode();
        viewModel.onOtpChanged("123456");
        viewModel.onNewPasswordChanged("newpass12");
        viewModel.onConfirmPasswordChanged("newpass12");
        viewModel.submitReset();
        assertEquals(
                List.of(ForgotPasswordViewModel.Event.CodeSent,
                        ForgotPasswordViewModel.Event.PasswordChanged),
                events);
        ForgotPasswordViewModel.UiState state = viewModel.getUiState().getValue();
        assertEquals("", state.getOtp());
        assertEquals("", state.getNewPassword());
        assertEquals("", state.getConfirmPassword());
        verify(repository).confirmReset(eq("+84912345678"), eq("123456"), eq("newpass12"));
    }

    @Test
    public void submitResetMapsInvalidOrExpiredCodeToInlineFlag() {
        when(repository.requestResetCode(anyString()))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        when(repository.confirmReset(anyString(), anyString(), anyString()))
                .thenReturn(Single.just(Result.failure(ApiError.InvalidOrExpiredCode.INSTANCE)));
        viewModel.onPhoneChanged("+84912345678");
        viewModel.sendCode();
        viewModel.onOtpChanged("000000");
        viewModel.onNewPasswordChanged("newpass12");
        viewModel.onConfirmPasswordChanged("newpass12");
        viewModel.submitReset();
        ForgotPasswordViewModel.UiState state = viewModel.getUiState().getValue();
        assertTrue(state.isCodeError());
        assertEquals(ApiError.InvalidOrExpiredCode.INSTANCE, state.getError());
    }

    @Test
    public void resendIgnoredWhileCooldownActive() {
        when(repository.requestResetCode(anyString()))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        viewModel.onPhoneChanged("+84912345678");
        viewModel.sendCode();
        assertTrue(viewModel.getUiState().getValue().getResendSecondsRemaining() > 0);
        viewModel.resendCode();
        verify(repository, times(1)).requestResetCode(anyString());
    }

    @Test
    public void cooldownTicksDownToZeroWithoutBackgroundThreads() {
        when(repository.requestResetCode(anyString()))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        viewModel.onPhoneChanged("+84912345678");
        viewModel.sendCode();

        clock.advanceSeconds(30);
        tickerScheduler.advanceTimeBy(30, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(30, viewModel.getUiState().getValue().getResendSecondsRemaining());

        clock.advanceSeconds(30);
        tickerScheduler.advanceTimeBy(30, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(0, viewModel.getUiState().getValue().getResendSecondsRemaining());
    }

    @Test
    public void submitShowsMismatchWhileButtonStaysEnabledForCompleteOtp() {
        when(repository.requestResetCode(anyString()))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        viewModel.onPhoneChanged("+84912345678");
        viewModel.sendCode();
        viewModel.onOtpChanged("123456");
        viewModel.onNewPasswordChanged("newpass12");
        viewModel.onConfirmPasswordChanged("newpass13");
        viewModel.submitReset();
        ForgotPasswordViewModel.UiState state = viewModel.getUiState().getValue();
        assertTrue(state.isConfirmMismatch());
        verify(repository, never()).confirmReset(anyString(), anyString(), anyString());
    }

    @Test
    public void submitRejectsTooLongPasswordLocally() {
        when(repository.requestResetCode(anyString()))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        viewModel.onPhoneChanged("+84912345678");
        viewModel.sendCode();
        viewModel.onOtpChanged("123456");
        String tooLong = "a".repeat(73);
        viewModel.onNewPasswordChanged(tooLong);
        viewModel.onConfirmPasswordChanged(tooLong);
        viewModel.submitReset();
        assertTrue(viewModel.getUiState().getValue().isPasswordTooLong());
        verify(repository, never()).confirmReset(anyString(), anyString(), anyString());
    }

    @Test
    public void resendIgnoredWhileSubmitting() {
        when(repository.requestResetCode(anyString()))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        when(repository.confirmReset(anyString(), anyString(), anyString()))
                .thenReturn(Single.create(emitter -> { /* in-flight */ }));
        viewModel.onPhoneChanged("+84912345678");
        viewModel.sendCode();
        viewModel.onOtpChanged("123456");
        viewModel.onNewPasswordChanged("newpass12");
        viewModel.onConfirmPasswordChanged("newpass12");
        viewModel.submitReset();
        assertTrue(viewModel.getUiState().getValue().isSubmitting());
        clock.advanceSeconds(ForgotPasswordViewModel.RESEND_COOLDOWN_SECONDS + 1L);
        viewModel.resendCode();
        verify(repository, times(1)).requestResetCode(anyString());
    }

    @Test
    public void secretsAreNotWrittenToSavedStateHandle() {
        SavedStateHandle handle = new SavedStateHandle();
        ForgotPasswordViewModel local =
                new ForgotPasswordViewModel(repository, handle, clock, tickerScheduler);
        local.onOtpChanged("123456");
        local.onNewPasswordChanged("secret-pass");
        local.onConfirmPasswordChanged("secret-pass");
        assertFalse(handle.keys().stream().anyMatch(
                key -> key.contains("otp") || key.contains("password")));
    }

    @Test
    public void changePhoneEntryRestoresThePhoneStepForCorrection() {
        when(repository.requestResetCode(anyString()))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        viewModel.onPhoneChanged("+84912345678");
        viewModel.sendCode();
        viewModel.onOtpChanged("123456");
        viewModel.onNewPasswordChanged("newpass12");
        viewModel.onConfirmPasswordChanged("newpass12");

        viewModel.changePhoneEntry();

        ForgotPasswordViewModel.UiState state = viewModel.getUiState().getValue();
        assertFalse(state.isCodeRequested());
        assertFalse(state.isSending());
        assertFalse(state.isResending());
        assertEquals("+84912345678", state.getPhone());
        assertEquals("", state.getOtp());
        assertEquals("", state.getNewPassword());
        assertEquals(0, state.getResendSecondsRemaining());
    }

    @Test
    public void submitRejectsShortPasswordLocally() {
        when(repository.requestResetCode(anyString()))
                .thenReturn(Single.just(new Result.Success<>(Unit.INSTANCE)));
        viewModel.onPhoneChanged("+84912345678");
        viewModel.sendCode();
        viewModel.onOtpChanged("123456");
        viewModel.onNewPasswordChanged("short");
        viewModel.onConfirmPasswordChanged("short");
        viewModel.submitReset();
        assertTrue(viewModel.getUiState().getValue().isPasswordInvalid());
        verify(repository, never()).confirmReset(anyString(), anyString(), anyString());
    }

    private List<ForgotPasswordViewModel.Event> observeEvents() {
        List<ForgotPasswordViewModel.Event> events = new ArrayList<>();
        LiveData<ForgotPasswordViewModel.Event> liveData = viewModel.getEvents();
        liveData.observeForever(events::add);
        return events;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant start) {
            instant = start;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
