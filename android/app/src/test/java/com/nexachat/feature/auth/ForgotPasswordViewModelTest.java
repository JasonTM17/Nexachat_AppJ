package com.nexachat.feature.auth;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.SavedStateHandle;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.common.Unit;
import com.nexachat.data.PasswordResetRepository;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
    private ForgotPasswordViewModel viewModel;

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(scheduler -> Schedulers.trampoline());
        repository = mock(PasswordResetRepository.class);
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        SavedStateHandle handle = new SavedStateHandle();
        viewModel = new ForgotPasswordViewModel(repository, handle, clock);
    }

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
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
        assertEquals(List.of(ForgotPasswordViewModel.Event.PasswordChanged), events);
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
