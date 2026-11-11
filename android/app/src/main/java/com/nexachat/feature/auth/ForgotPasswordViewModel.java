package com.nexachat.feature.auth;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.SavedStateHandle;
import androidx.lifecycle.ViewModel;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.data.PasswordResetRepository;
import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Scheduler;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import javax.inject.Inject;

@HiltViewModel
public final class ForgotPasswordViewModel extends ViewModel {

    public static final String ARG_PREFILL_PHONE = "prefillPhone";
    public static final String RESULT_PASSWORD_CHANGED = "forgot_password_changed";
    public static final int RESEND_COOLDOWN_SECONDS = 60;

    private static final String KEY_PHONE = "forgot_phone";
    private static final String KEY_CODE_REQUESTED = "forgot_code_requested";
    private static final String KEY_RESEND_UNTIL_MS = "forgot_resend_until_ms";

    private final PasswordResetRepository repository;
    private final SavedStateHandle savedState;
    private final Clock clock;
    private final Scheduler tickerScheduler;
    private final CompositeDisposable disposables = new CompositeDisposable();
    @Nullable private Disposable ticker;
    private final MutableLiveData<UiState> mutableUiState = new MutableLiveData<>(new UiState());
    private UiState currentState = mutableUiState.getValue();
    private final OneShotLiveData<Event> events = new OneShotLiveData<>();

    @Inject
    public ForgotPasswordViewModel(
            PasswordResetRepository repository,
            SavedStateHandle savedState) {
        this(repository, savedState, Clock.systemUTC(), Schedulers.computation());
    }

    ForgotPasswordViewModel(
            PasswordResetRepository repository,
            SavedStateHandle savedState,
            Clock clock) {
        this(repository, savedState, clock, Schedulers.computation());
    }

    ForgotPasswordViewModel(
            PasswordResetRepository repository,
            SavedStateHandle savedState,
            Clock clock,
            Scheduler tickerScheduler) {
        this.repository = Objects.requireNonNull(repository);
        this.savedState = Objects.requireNonNull(savedState);
        this.clock = Objects.requireNonNull(clock);
        this.tickerScheduler = Objects.requireNonNull(tickerScheduler);
        String prefill = savedState.get(ARG_PREFILL_PHONE);
        String storedPhone = savedState.get(KEY_PHONE);
        if (storedPhone == null && prefill != null && !prefill.isEmpty()) {
            storedPhone = prefill;
            savedState.set(KEY_PHONE, storedPhone);
        }
        final String initialPhone = storedPhone == null ? "" : storedPhone;
        Boolean requested = savedState.get(KEY_CODE_REQUESTED);
        updateState(state -> state
                .withPhone(initialPhone)
                .withCodeRequested(requested != null && requested));
        Long resendUntil = savedState.get(KEY_RESEND_UNTIL_MS);
        if (resendUntil != null) {
            updateResendSeconds(resendUntil);
        }
        armTickerIfNeeded();
    }

    public LiveData<UiState> getUiState() {
        return mutableUiState;
    }

    public LiveData<Event> getEvents() {
        return events;
    }

    public void onPhoneChanged(@Nullable String raw) {
        if (currentState().isCodeRequested()) {
            return;
        }
        String next = raw == null ? "" : raw;
        savedState.set(KEY_PHONE, next);
        updateState(state -> state
                .withPhone(next)
                .withPhoneInvalid(false)
                .withError(null));
    }

    public void sendCode() {
        UiState state = currentState();
        if (state.isSending() || state.isCodeRequested()) {
            return;
        }
        String phone = AuthFieldValidators.normalizeE164(state.getPhone());
        if (!AuthFieldValidators.isE164(phone)) {
            updateState(current -> current.withPhoneInvalid(true));
            return;
        }
        savedState.set(KEY_PHONE, phone);
        updateState(current -> current
                .withPhone(phone)
                .withSending(true)
                .withError(null)
                .withPhoneInvalid(false));
        disposables.add(repository.requestResetCode(phone)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        result -> updateState(current -> {
                            if (result instanceof Result.Success<?>) {
                                long until = clock.millis()
                                        + TimeUnit.SECONDS.toMillis(RESEND_COOLDOWN_SECONDS);
                                savedState.set(KEY_CODE_REQUESTED, true);
                                savedState.set(KEY_RESEND_UNTIL_MS, until);
                                armTickerIfNeeded();
                                events.postValue(Event.CodeSent);
                                return current
                                        .withSending(false)
                                        .withCodeRequested(true)
                                        .withResendSecondsRemaining(RESEND_COOLDOWN_SECONDS);
                            }
                            return current.withSending(false).withError(
                                    ((Result.Failure<?>) result).getError());
                        }),
                        error -> updateState(current -> current.withSending(false).withError(
                                new ApiError.Network(error.getMessage())))));
    }

    public void onOtpChanged(@Nullable String raw) {
        String sanitized = PasswordResetOtpSanitizer.sanitize(raw == null ? "" : raw);
        updateState(state -> state
                .withOtp(sanitized)
                .withCodeError(false)
                .withError(null));
    }

    public void onNewPasswordChanged(@Nullable String raw) {
        String next = raw == null ? "" : raw;
        updateState(state -> state
                .withNewPassword(next)
                .withPasswordInvalid(false)
                .withPasswordTooLong(false)
                .withConfirmMismatch(false)
                .withError(null));
    }

    public void onConfirmPasswordChanged(@Nullable String raw) {
        String next = raw == null ? "" : raw;
        updateState(state -> state
                .withConfirmPassword(next)
                .withConfirmMismatch(false)
                .withError(null));
    }

    public void changePhoneEntry() {
        stopTicker();
        savedState.set(KEY_CODE_REQUESTED, false);
        savedState.remove(KEY_RESEND_UNTIL_MS);
        updateState(state -> state
                .withCodeRequested(false)
                .withOtp("")
                .withNewPassword("")
                .withConfirmPassword("")
                .withCodeError(false)
                .withPasswordInvalid(false)
                .withPasswordTooLong(false)
                .withConfirmMismatch(false)
                .withSending(false)
                .withResending(false)
                .withSubmitting(false)
                .withResendSecondsRemaining(0)
                .withError(null));
    }

    public void submitReset() {
        UiState state = currentState();
        if (state.isSubmitting() || state.isResending() || !state.isCodeRequested()) {
            return;
        }
        if (!PasswordResetOtpSanitizer.isComplete(state.getOtp())) {
            return;
        }
        boolean passwordInvalid = PasswordPolicyValidator.isTooShort(state.getNewPassword());
        boolean passwordTooLong = PasswordPolicyValidator.isTooLong(state.getNewPassword());
        boolean mismatch = !AuthFieldValidators.passwordsMatch(
                state.getNewPassword(), state.getConfirmPassword());
        if (passwordInvalid || passwordTooLong || mismatch) {
            updateState(current -> current
                    .withPasswordInvalid(passwordInvalid)
                    .withPasswordTooLong(passwordTooLong)
                    .withConfirmMismatch(mismatch));
            return;
        }
        updateState(current -> current
                .withSubmitting(true)
                .withCodeError(false)
                .withError(null)
                .withPasswordInvalid(false)
                .withPasswordTooLong(false)
                .withConfirmMismatch(false));
        disposables.add(repository.confirmReset(
                        state.getPhone(), state.getOtp(), state.getNewPassword())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                        result -> updateState(current -> {
                            if (result instanceof Result.Success<?>) {
                                events.postValue(Event.PasswordChanged);
                                return current.withSubmitting(false);
                            }
                            ApiError error = ((Result.Failure<?>) result).getError();
                            boolean invalid = error instanceof ApiError.InvalidOrExpiredCode;
                            return current.withSubmitting(false)
                                    .withError(error)
                                    .withCodeError(invalid);
                        }),
                        error -> updateState(current -> current.withSubmitting(false).withError(
                                new ApiError.Network(error.getMessage())))));
    }

    public void resendCode() {
        UiState state = currentState();
        if (!state.isCodeRequested()
                || state.isResending()
                || state.isSubmitting()
                || state.getResendSecondsRemaining() > 0) {
            return;
        }
        updateState(current -> current.withResending(true).withError(null).withCodeError(false));
        disposables.add(repository.requestResetCode(state.getPhone())
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
        ticker = io.reactivex.rxjava3.core.Observable.interval(
                        0, 1, TimeUnit.SECONDS, tickerScheduler)
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

    public enum Event {
        CodeSent,
        PasswordChanged
    }

    public static final class UiState {
        private final String phone;
        private final String otp;
        private final String newPassword;
        private final String confirmPassword;
        private final boolean codeRequested;
        private final boolean phoneInvalid;
        private final boolean passwordInvalid;
        private final boolean passwordTooLong;
        private final boolean confirmMismatch;
        private final boolean codeError;
        private final boolean sending;
        private final boolean resending;
        private final boolean submitting;
        private final int resendSecondsRemaining;
        @Nullable private final ApiError error;

        public UiState() {
            phone = "";
            otp = "";
            newPassword = "";
            confirmPassword = "";
            codeRequested = false;
            phoneInvalid = false;
            passwordInvalid = false;
            passwordTooLong = false;
            confirmMismatch = false;
            codeError = false;
            sending = false;
            resending = false;
            submitting = false;
            resendSecondsRemaining = 0;
            error = null;
        }

        private UiState(
                String phone,
                String otp,
                String newPassword,
                String confirmPassword,
                boolean codeRequested,
                boolean phoneInvalid,
                boolean passwordInvalid,
                boolean passwordTooLong,
                boolean confirmMismatch,
                boolean codeError,
                boolean sending,
                boolean resending,
                boolean submitting,
                int resendSecondsRemaining,
                @Nullable ApiError error) {
            this.phone = phone;
            this.otp = otp;
            this.newPassword = newPassword;
            this.confirmPassword = confirmPassword;
            this.codeRequested = codeRequested;
            this.phoneInvalid = phoneInvalid;
            this.passwordInvalid = passwordInvalid;
            this.passwordTooLong = passwordTooLong;
            this.confirmMismatch = confirmMismatch;
            this.codeError = codeError;
            this.sending = sending;
            this.resending = resending;
            this.submitting = submitting;
            this.resendSecondsRemaining = resendSecondsRemaining;
            this.error = error;
        }

        public String getPhone() {
            return phone;
        }

        public String getOtp() {
            return otp;
        }

        public String getNewPassword() {
            return newPassword;
        }

        public String getConfirmPassword() {
            return confirmPassword;
        }

        public boolean isCodeRequested() {
            return codeRequested;
        }

        public boolean isPhoneInvalid() {
            return phoneInvalid;
        }

        public boolean isPasswordInvalid() {
            return passwordInvalid;
        }

        public boolean isPasswordTooLong() {
            return passwordTooLong;
        }

        public boolean isConfirmMismatch() {
            return confirmMismatch;
        }

        public boolean isCodeError() {
            return codeError;
        }

        public boolean isSending() {
            return sending;
        }

        public boolean isResending() {
            return resending;
        }

        public boolean isSubmitting() {
            return submitting;
        }

        public int getResendSecondsRemaining() {
            return resendSecondsRemaining;
        }

        @Nullable
        public ApiError getError() {
            return error;
        }

        public UiState withPhone(String value) {
            return copy(phone -> value);
        }

        public UiState withOtp(String value) {
            return new UiState(
                    phone, value, newPassword, confirmPassword, codeRequested, phoneInvalid,
                    passwordInvalid, passwordTooLong, confirmMismatch, codeError, sending, resending, submitting,
                    resendSecondsRemaining, error);
        }

        public UiState withNewPassword(String value) {
            return new UiState(
                    phone, otp, value, confirmPassword, codeRequested, phoneInvalid,
                    passwordInvalid, passwordTooLong, confirmMismatch, codeError, sending, resending, submitting,
                    resendSecondsRemaining, error);
        }

        public UiState withConfirmPassword(String value) {
            return new UiState(
                    phone, otp, newPassword, value, codeRequested, phoneInvalid,
                    passwordInvalid, passwordTooLong, confirmMismatch, codeError, sending, resending, submitting,
                    resendSecondsRemaining, error);
        }

        public UiState withCodeRequested(boolean value) {
            return new UiState(
                    phone, otp, newPassword, confirmPassword, value, phoneInvalid,
                    passwordInvalid, passwordTooLong, confirmMismatch, codeError, sending, resending, submitting,
                    resendSecondsRemaining, error);
        }

        public UiState withPhoneInvalid(boolean value) {
            return new UiState(
                    phone, otp, newPassword, confirmPassword, codeRequested, value,
                    passwordInvalid, passwordTooLong, confirmMismatch, codeError, sending, resending, submitting,
                    resendSecondsRemaining, error);
        }

        public UiState withPasswordInvalid(boolean value) {
            return new UiState(
                    phone, otp, newPassword, confirmPassword, codeRequested, phoneInvalid,
                    value, passwordTooLong, confirmMismatch, codeError, sending, resending, submitting,
                    resendSecondsRemaining, error);
        }

        public UiState withPasswordTooLong(boolean value) {
            return new UiState(
                    phone, otp, newPassword, confirmPassword, codeRequested, phoneInvalid,
                    passwordInvalid, value, confirmMismatch, codeError, sending, resending, submitting,
                    resendSecondsRemaining, error);
        }

        public UiState withConfirmMismatch(boolean value) {
            return new UiState(
                    phone, otp, newPassword, confirmPassword, codeRequested, phoneInvalid,
                    passwordInvalid, passwordTooLong, value, codeError, sending, resending, submitting,
                    resendSecondsRemaining, error);
        }

        public UiState withCodeError(boolean value) {
            return new UiState(
                    phone, otp, newPassword, confirmPassword, codeRequested, phoneInvalid,
                    passwordInvalid, passwordTooLong, confirmMismatch, value, sending, resending, submitting,
                    resendSecondsRemaining, error);
        }

        public UiState withSending(boolean value) {
            return new UiState(
                    phone, otp, newPassword, confirmPassword, codeRequested, phoneInvalid,
                    passwordInvalid, passwordTooLong, confirmMismatch, codeError, value, resending, submitting,
                    resendSecondsRemaining, error);
        }

        public UiState withResending(boolean value) {
            return new UiState(
                    phone, otp, newPassword, confirmPassword, codeRequested, phoneInvalid,
                    passwordInvalid, passwordTooLong, confirmMismatch, codeError, sending, value, submitting,
                    resendSecondsRemaining, error);
        }

        public UiState withSubmitting(boolean value) {
            return new UiState(
                    phone, otp, newPassword, confirmPassword, codeRequested, phoneInvalid,
                    passwordInvalid, passwordTooLong, confirmMismatch, codeError, sending, resending, value,
                    resendSecondsRemaining, error);
        }

        public UiState withResendSecondsRemaining(int value) {
            return new UiState(
                    phone, otp, newPassword, confirmPassword, codeRequested, phoneInvalid,
                    passwordInvalid, passwordTooLong, confirmMismatch, codeError, sending, resending, submitting,
                    value, error);
        }

        public UiState withError(@Nullable ApiError value) {
            return new UiState(
                    phone, otp, newPassword, confirmPassword, codeRequested, phoneInvalid,
                    passwordInvalid, passwordTooLong, confirmMismatch, codeError, sending, resending, submitting,
                    resendSecondsRemaining, value);
        }

        private UiState copy(java.util.function.Function<String, String> phoneMapper) {
            return new UiState(
                    phoneMapper.apply(phone), otp, newPassword, confirmPassword, codeRequested,
                    phoneInvalid, passwordInvalid, passwordTooLong, confirmMismatch, codeError, sending, resending,
                    submitting, resendSecondsRemaining, error);
        }
    }
}
