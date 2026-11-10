package com.nexachat.feature.profile;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.dto.VerifyRecoveryEmailResponse;
import com.nexachat.data.RecoveryEmailRepository;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import javax.inject.Inject;

@HiltViewModel
public final class RecoveryEmailOtpViewModel extends ViewModel {

    static final String ARG_EMAIL = "email";
    private static final String KEY_OTP = "recovery_otp";
    private static final String KEY_RESEND_UNTIL_MS = "recovery_resend_until_ms";
    public static final int RESEND_COOLDOWN_SECONDS = 60;

    private final RecoveryEmailRepository repository;
    private final SavedStateHandle savedState;
    private final Clock clock;
    private final CompositeDisposable disposables = new CompositeDisposable();
    @Nullable private Disposable ticker;
    private final MutableLiveData<UiState> mutableUiState = new MutableLiveData<>(new UiState());
    private UiState currentState = mutableUiState.getValue();

    @Inject
    public RecoveryEmailOtpViewModel(
            RecoveryEmailRepository repository,
            SavedStateHandle savedState) {
        this(repository, savedState, Clock.systemUTC());
    }

    RecoveryEmailOtpViewModel(
            RecoveryEmailRepository repository,
            SavedStateHandle savedState,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.savedState = Objects.requireNonNull(savedState);
        this.clock = Objects.requireNonNull(clock);
        String email = savedState.get(ARG_EMAIL);
        String otp = savedState.get(KEY_OTP);
        Long resendUntil = savedState.get(KEY_RESEND_UNTIL_MS);
        updateState(state -> state
                .withEmail(email == null ? "" : email)
                .withOtp(otp == null ? "" : otp));
        if (resendUntil != null) {
            updateResendSeconds(resendUntil);
        }
        armTickerIfNeeded();
    }

    public LiveData<UiState> getUiState() {
        return mutableUiState;
    }

    public void onOtpChanged(@Nullable String raw) {
        String sanitized = RecoveryEmailOtpSanitizer.sanitize(raw == null ? "" : raw);
        savedState.set(KEY_OTP, sanitized);
        updateState(state -> state
                .withOtp(sanitized)
                .withCodeError(false)
                .withError(null));
    }

    public void verify() {
        UiState state = currentState();
        if (state.isVerifying() || !RecoveryEmailOtpSanitizer.isComplete(state.getOtp())) {
            return;
        }
        updateState(current -> current.withVerifying(true).withCodeError(false).withError(null));
        disposables.add(repository.verifyCode(state.getOtp())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        result -> updateState(current -> {
                            if (result instanceof Result.Success<?> success) {
                                VerifyRecoveryEmailResponse body =
                                        (VerifyRecoveryEmailResponse) success.getValue();
                                return current.withVerifying(false)
                                        .withVerifiedEmail(body.getEmail())
                                        .withVerified(true);
                            }
                            ApiError error = ((Result.Failure<?>) result).getError();
                            boolean invalid = error instanceof ApiError.InvalidOrExpiredCode;
                            return current.withVerifying(false)
                                    .withError(error)
                                    .withCodeError(invalid);
                        }),
                        error -> updateState(current -> current.withVerifying(false).withError(
                                new ApiError.Network(error.getMessage())))));
    }

    public void resendCode() {
        UiState state = currentState();
        if (state.isResending() || state.getResendSecondsRemaining() > 0) {
            return;
        }
        String email = state.getEmail();
        if (email.isEmpty()) {
            return;
        }
        updateState(current -> current.withResending(true).withError(null).withCodeError(false));
        disposables.add(repository.requestVerificationCode(email)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        result -> updateState(current -> {
                            if (result instanceof Result.Success<?>) {
                                long until = clock.millis()
                                        + TimeUnit.SECONDS.toMillis(RESEND_COOLDOWN_SECONDS);
                                savedState.set(KEY_RESEND_UNTIL_MS, until);
                                armTickerIfNeeded();
                                return current.withResending(false)
                                        .withResendSecondsRemaining(RESEND_COOLDOWN_SECONDS);
                            }
                            return current.withResending(false).withError(
                                    ((Result.Failure<?>) result).getError());
                        }),
                        error -> updateState(current -> current.withResending(false).withError(
                                new ApiError.Network(error.getMessage())))));
    }

    public void noteCodeRequested() {
        long until = clock.millis() + TimeUnit.SECONDS.toMillis(RESEND_COOLDOWN_SECONDS);
        savedState.set(KEY_RESEND_UNTIL_MS, until);
        updateState(state -> state.withResendSecondsRemaining(RESEND_COOLDOWN_SECONDS));
        armTickerIfNeeded();
    }

    public void consumeVerified() {
        updateState(state -> state.withVerified(false));
    }

    private void armTickerIfNeeded() {
        Long until = savedState.get(KEY_RESEND_UNTIL_MS);
        if (until == null || until <= clock.millis()) {
            stopTicker();
            updateState(state -> state.withResendSecondsRemaining(0));
            return;
        }
        if (ticker != null && !ticker.isDisposed()) {
            return;
        }
        ticker = io.reactivex.rxjava3.core.Observable.interval(0, 1, TimeUnit.SECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(ignored -> {
                    Long deadline = savedState.get(KEY_RESEND_UNTIL_MS);
                    if (deadline == null) {
                        stopTicker();
                        updateState(state -> state.withResendSecondsRemaining(0));
                        return;
                    }
                    updateResendSeconds(deadline);
                });
        disposables.add(ticker);
    }

    private void updateResendSeconds(long untilMs) {
        long remainingMs = untilMs - clock.millis();
        int seconds = remainingMs <= 0
                ? 0
                : (int) Math.min(RESEND_COOLDOWN_SECONDS,
                        TimeUnit.MILLISECONDS.toSeconds(remainingMs)
                                + (remainingMs % 1000 == 0 ? 0 : 1));
        updateState(state -> state.withResendSecondsRemaining(seconds));
        if (seconds == 0) {
            stopTicker();
        }
    }

    private void stopTicker() {
        if (ticker != null) {
            ticker.dispose();
            ticker = null;
        }
    }

    private synchronized UiState currentState() {
        return currentState;
    }

    private synchronized void updateState(UnaryOperator<UiState> reducer) {
        currentState = Objects.requireNonNull(reducer.apply(currentState), "state");
        mutableUiState.postValue(currentState);
    }

    @Override
    protected void onCleared() {
        stopTicker();
        disposables.clear();
        super.onCleared();
    }

    public static final class UiState {
        private final String email;
        private final String otp;
        private final int resendSecondsRemaining;
        private final boolean verifying;
        private final boolean resending;
        private final boolean codeError;
        @Nullable private final ApiError error;
        private final boolean verified;
        @Nullable private final String verifiedEmail;

        public UiState() {
            email = "";
            otp = "";
            resendSecondsRemaining = 0;
            verifying = false;
            resending = false;
            codeError = false;
            error = null;
            verified = false;
            verifiedEmail = null;
        }

        private UiState(
                String email,
                String otp,
                int resendSecondsRemaining,
                boolean verifying,
                boolean resending,
                boolean codeError,
                @Nullable ApiError error,
                boolean verified,
                @Nullable String verifiedEmail) {
            this.email = email;
            this.otp = otp;
            this.resendSecondsRemaining = resendSecondsRemaining;
            this.verifying = verifying;
            this.resending = resending;
            this.codeError = codeError;
            this.error = error;
            this.verified = verified;
            this.verifiedEmail = verifiedEmail;
        }

        public String getEmail() {
            return email;
        }

        public String getOtp() {
            return otp;
        }

        public int getResendSecondsRemaining() {
            return resendSecondsRemaining;
        }

        public boolean isVerifying() {
            return verifying;
        }

        public boolean isResending() {
            return resending;
        }

        public boolean isCodeError() {
            return codeError;
        }

        @Nullable
        public ApiError getError() {
            return error;
        }

        public boolean isVerified() {
            return verified;
        }

        @Nullable
        public String getVerifiedEmail() {
            return verifiedEmail;
        }

        public UiState withEmail(String value) {
            return new UiState(
                    value, otp, resendSecondsRemaining, verifying, resending,
                    codeError, error, verified, verifiedEmail);
        }

        public UiState withOtp(String value) {
            return new UiState(
                    email, value, resendSecondsRemaining, verifying, resending,
                    codeError, error, verified, verifiedEmail);
        }

        public UiState withResendSecondsRemaining(int value) {
            return new UiState(
                    email, otp, value, verifying, resending, codeError, error, verified, verifiedEmail);
        }

        public UiState withVerifying(boolean value) {
            return new UiState(
                    email, otp, resendSecondsRemaining, value, resending,
                    codeError, error, verified, verifiedEmail);
        }

        public UiState withResending(boolean value) {
            return new UiState(
                    email, otp, resendSecondsRemaining, verifying, value,
                    codeError, error, verified, verifiedEmail);
        }

        public UiState withCodeError(boolean value) {
            return new UiState(
                    email, otp, resendSecondsRemaining, verifying, resending,
                    value, error, verified, verifiedEmail);
        }

        public UiState withError(@Nullable ApiError value) {
            return new UiState(
                    email, otp, resendSecondsRemaining, verifying, resending,
                    codeError, value, verified, verifiedEmail);
        }

        public UiState withVerified(boolean value) {
            return new UiState(
                    email, otp, resendSecondsRemaining, verifying, resending,
                    codeError, error, value, verifiedEmail);
        }

        public UiState withVerifiedEmail(@Nullable String value) {
            return new UiState(
                    email, otp, resendSecondsRemaining, verifying, resending,
                    codeError, error, verified, value);
        }
    }
}
