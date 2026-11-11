package com.nexachat.feature.profile;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.SavedStateHandle;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.dto.VerifyRecoveryEmailResponse;
import io.reactivex.rxjava3.android.plugins.RxAndroidPlugins;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.schedulers.TestScheduler;
import io.reactivex.rxjava3.subjects.SingleSubject;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

public final class RecoveryEmailOtpViewModelTest {

    @Rule public final InstantTaskExecutorRule task = new InstantTaskExecutorRule();

    private com.nexachat.data.RecoveryEmailRepository repository;
    private static final Instant FIXED = Instant.parse("2026-11-10T10:00:00Z");
    private MutableClock clock;
    private TestScheduler tickerScheduler;

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(ignored -> Schedulers.trampoline());
        RxAndroidPlugins.setInitMainThreadSchedulerHandler(ignored -> Schedulers.trampoline());
        repository = mock(com.nexachat.data.RecoveryEmailRepository.class);
        clock = new MutableClock(FIXED);
        tickerScheduler = new TestScheduler();
    }

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
        RxAndroidPlugins.reset();
    }

    private RecoveryEmailOtpViewModel createViewModel() {
        SavedStateHandle handle = new SavedStateHandle();
        handle.set(RecoveryEmailOtpViewModel.ARG_EMAIL, "user@test.com");
        return new RecoveryEmailOtpViewModel(repository, handle, clock, tickerScheduler);
    }

    @Test
    public void verifySuccessMarksVerified() {
        when(repository.verifyCode(anyString()))
                .thenReturn(Single.just(new Result.Success<>(
                        new VerifyRecoveryEmailResponse("user@test.com", true))));
        RecoveryEmailOtpViewModel viewModel = createViewModel();
        viewModel.onOtpChanged("123456");
        viewModel.verify();

        RecoveryEmailOtpViewModel.UiState state = viewModel.getUiState().getValue();
        assertTrue(state.isVerified());
        assertEquals("user@test.com", state.getVerifiedEmail());
        assertEquals("", state.getOtp());
    }

    @Test
    public void invalidCodeSetsInlineErrorFlag() {
        when(repository.verifyCode(anyString()))
                .thenReturn(Single.just(Result.failure(ApiError.InvalidOrExpiredCode.INSTANCE)));
        RecoveryEmailOtpViewModel viewModel = createViewModel();
        viewModel.onOtpChanged("000000");
        viewModel.verify();

        RecoveryEmailOtpViewModel.UiState state = viewModel.getUiState().getValue();
        assertTrue(state.isCodeError());
        assertFalse(state.isVerified());
    }

    @Test
    public void noteCodeRequestedStartsSixtySecondCooldown() {
        RecoveryEmailOtpViewModel viewModel = createViewModel();
        viewModel.noteCodeRequested();

        assertEquals(60, viewModel.getUiState().getValue().getResendSecondsRemaining());
    }

    @Test
    public void resendBlockedDuringCooldown() {
        when(repository.requestVerificationCode(anyString()))
                .thenReturn(Single.just(new Result.Success<>(com.nexachat.core.common.Unit.INSTANCE)));
        RecoveryEmailOtpViewModel viewModel = createViewModel();
        viewModel.noteCodeRequested();
        viewModel.resendCode();

        assertEquals(60, viewModel.getUiState().getValue().getResendSecondsRemaining());
    }

    @Test
    public void verifyBlockedWhileResendIsInFlight() {
        SingleSubject<Result<com.nexachat.core.common.Unit>> pending = SingleSubject.create();
        when(repository.requestVerificationCode(anyString())).thenReturn(pending);
        RecoveryEmailOtpViewModel viewModel = createViewModel();
        viewModel.onOtpChanged("123456");
        viewModel.resendCode();
        assertTrue(viewModel.getUiState().getValue().isResending());

        viewModel.verify();

        assertFalse(viewModel.getUiState().getValue().isVerifying());
        verify(repository, never()).verifyCode(anyString());

        pending.onSuccess(new Result.Success<>(com.nexachat.core.common.Unit.INSTANCE));
        assertFalse(viewModel.getUiState().getValue().isResending());
    }

    @Test
    public void resendBlockedWhileVerifyIsInFlight() {
        SingleSubject<Result<VerifyRecoveryEmailResponse>> pending = SingleSubject.create();
        when(repository.verifyCode(anyString())).thenReturn(pending);
        RecoveryEmailOtpViewModel viewModel = createViewModel();
        viewModel.onOtpChanged("123456");
        viewModel.verify();
        assertTrue(viewModel.getUiState().getValue().isVerifying());

        viewModel.resendCode();

        assertFalse(viewModel.getUiState().getValue().isResending());
        verify(repository, never()).requestVerificationCode(anyString());
        verify(repository, times(1)).verifyCode("123456");

        pending.onSuccess(new Result.Success<>(
                new VerifyRecoveryEmailResponse("user@test.com", true)));
        assertTrue(viewModel.getUiState().getValue().isVerified());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant start) {
            instant = start;
        }

        @Override
        public ZoneId getZone() {
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
