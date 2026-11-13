package com.nexachat.feature.profile;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.nexachat.core.auth.GoogleSignInFlow;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.common.Unit;
import com.nexachat.core.network.dto.LinkedIdentitiesResponse;
import com.nexachat.data.AuthRepository;

import java.util.Objects;
import java.util.function.UnaryOperator;

import javax.inject.Inject;

import dagger.hilt.android.lifecycle.HiltViewModel;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * "Linked accounts" state on the profile screen: load, link (password + Google
 * credential) and unlink (password). Hidden entirely when the build has no
 * Google web client ID.
 */
@HiltViewModel
public final class LinkedAccountsViewModel extends ViewModel {

    private final AuthRepository authRepository;
    private final GoogleSignInFlow googleSignInFlow;
    private final CompositeDisposable disposables = new CompositeDisposable();
    private final MutableLiveData<UiState> mutableUiState;
    private UiState currentState;

    @Inject
    public LinkedAccountsViewModel(AuthRepository authRepository,
                                   GoogleSignInFlow googleSignInFlow) {
        this.authRepository = Objects.requireNonNull(authRepository, "authRepository");
        this.googleSignInFlow = Objects.requireNonNull(googleSignInFlow, "googleSignInFlow");
        this.currentState = new UiState(
                googleSignInFlow.isConfigured(), false, null, false, null, null);
        this.mutableUiState = new MutableLiveData<>(currentState);
        if (googleSignInFlow.isConfigured()) {
            load();
        }
    }

    public LiveData<UiState> getUiState() {
        return mutableUiState;
    }

    public boolean isConfigured() {
        return googleSignInFlow.isConfigured();
    }

    public void load() {
        if (!googleSignInFlow.isConfigured()) {
            return;
        }
        update(state -> state.withLoading(true).withError(null));
        disposables.add(authRepository.linkedIdentities()
                .subscribeOn(Schedulers.io())
                .subscribe(this::onIdentities, error -> update(state -> state
                        .withLoading(false)
                        .withError(new ApiError.Network(error.getMessage())))));
    }

    public void link(Context activity, String password) {
        if (currentState().isWorking() || password == null || password.isEmpty()) {
            return;
        }
        update(state -> state.withLoading(true).withError(null));
        disposables.add(googleSignInFlow.link(activity, password)
                .subscribeOn(Schedulers.io())
                .subscribe(this::onLinkResult, error -> update(state -> state
                        .withLoading(false)
                        .withError(new ApiError.Network(error.getMessage())))));
    }

    public void unlink(String password) {
        if (currentState().isWorking() || password == null || password.isEmpty()) {
            return;
        }
        requestUnlink(password);
    }

    /**
     * Placeholder that satisfies {@code @NotBlank} on the unlink body. A blank
     * password is rejected as {@code VALIDATION_FAILED} and never reaches the
     * server branch that answers {@code LAST_SIGN_IN_METHOD} for an account
     * with no password hash.
     */
    static final String GOOGLE_ONLY_UNLINK_PASSWORD = "-";

    /** Google-only accounts: server rejects unlink with {@code LAST_SIGN_IN_METHOD}. */
    public void unlinkWithoutPassword() {
        if (currentState().isWorking()) {
            return;
        }
        requestUnlink(GOOGLE_ONLY_UNLINK_PASSWORD);
    }

    public void consumeError() {
        update(state -> state.withError(null));
    }

    public void consumeInlineError() {
        update(state -> state.withInlineError(null));
    }

    private void requestUnlink(String password) {
        update(state -> state.withLoading(true).withError(null).withInlineError(null));
        disposables.add(authRepository.unlinkGoogle(password)
                .subscribeOn(Schedulers.io())
                .subscribe(this::onUnlinkResult, error -> update(state -> state
                        .withLoading(false)
                        .withError(new ApiError.Network(error.getMessage())))));
    }

    private void onIdentities(Result<LinkedIdentitiesResponse> result) {
        if (result instanceof Result.Failure<?> failure) {
            if (isFeatureDisabled(failure.getError())) {
                // The client has a client ID but the server flag is off: hide the
                // row instead of showing an error the user cannot act on.
                update(state -> state.withConfigured(false).withLoading(false).withError(null));
                return;
            }
            update(state -> state.withLoading(false).withError(failure.getError()));
            return;
        }
        LinkedIdentitiesResponse response = result.getOrNull();
        String email = response == null || response.getIdentities().isEmpty()
                ? null : response.getIdentities().get(0).getEmail();
        boolean linked = response != null && !response.getIdentities().isEmpty();
        update(state -> state.withLoading(false).withLinked(linked, email).withError(null));
    }

    private void onLinkResult(Result<Unit> result) {
        if (result instanceof Result.Failure<?> failure) {
            if (failure.getError() instanceof ApiError.Unknown unknown
                    && GoogleSignInFlow.CODE_CANCELLED.equals(unknown.getCode())) {
                update(state -> state.withLoading(false).withError(null));
                return;
            }
            update(state -> state.withLoading(false).withError(failure.getError()));
            return;
        }
        load();
    }

    private void onUnlinkResult(Result<Unit> result) {
        if (result instanceof Result.Failure<?> failure) {
            ApiError error = failure.getError();
            if (shouldShowInlineSignInError(error)) {
                update(state -> state.withLoading(false)
                        .withInlineError(error)
                        .withError(null));
                return;
            }
            update(state -> state.withLoading(false).withError(error).withInlineError(null));
            return;
        }
        update(state -> state.withLoading(false)
                .withLinked(false, null)
                .withError(null)
                .withInlineError(null));
    }

    private static boolean shouldShowInlineSignInError(ApiError error) {
        if (error instanceof ApiError.WrongPassword) {
            return true;
        }
        return error instanceof ApiError.Unknown unknown
                && "LAST_SIGN_IN_METHOD".equals(unknown.getCode());
    }

    private static boolean isFeatureDisabled(ApiError error) {
        return error instanceof ApiError.Unknown unknown
                && "NOT_FOUND".equals(unknown.getCode());
    }

    private synchronized UiState currentState() {
        return currentState;
    }

    private synchronized void update(UnaryOperator<UiState> reducer) {
        currentState = Objects.requireNonNull(reducer.apply(currentState), "state");
        mutableUiState.postValue(currentState);
    }

    @Override
    protected void onCleared() {
        disposables.clear();
        super.onCleared();
    }

    /** Immutable observable state for the linked-accounts row. */
    public static final class UiState {
        private final boolean configured;
        private final boolean linked;
        @Nullable private final String email;
        private final boolean loading;
        @Nullable private final ApiError error;
        @Nullable private final ApiError inlineError;

        public UiState() {
            this(false, false, null, false, null, null);
        }

        public UiState(boolean configured, boolean linked, @Nullable String email,
                       boolean loading, @Nullable ApiError error,
                       @Nullable ApiError inlineError) {
            this.configured = configured;
            this.linked = linked;
            this.email = email;
            this.loading = loading;
            this.error = error;
            this.inlineError = inlineError;
        }

        public boolean isConfigured() {
            return configured;
        }

        public boolean isLinked() {
            return linked;
        }

        @Nullable
        public String getEmail() {
            return email;
        }

        public boolean isLoading() {
            return loading;
        }

        public boolean isWorking() {
            return loading;
        }

        @Nullable
        public ApiError getError() {
            return error;
        }

        @Nullable
        public ApiError getInlineError() {
            return inlineError;
        }

        public UiState withConfigured(boolean value) {
            return new UiState(value, linked, email, loading, error, inlineError);
        }

        public UiState withLinked(boolean value, @Nullable String newEmail) {
            return new UiState(configured, value, newEmail, loading, error, inlineError);
        }

        public UiState withLoading(boolean value) {
            return new UiState(configured, linked, email, value, error, inlineError);
        }

        public UiState withError(@Nullable ApiError newError) {
            return new UiState(configured, linked, email, loading, newError, inlineError);
        }

        public UiState withInlineError(@Nullable ApiError newError) {
            return new UiState(configured, linked, email, loading, error, newError);
        }
    }
}
