package com.nexachat.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.PhoneNormalizer;
import com.nexachat.core.common.Result;
import com.nexachat.core.common.WhitespaceTrim;
import com.nexachat.core.network.AuthApi;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.TokenRefreshOutcome;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.network.dto.AuthResponse;
import com.nexachat.core.network.dto.ChangePasswordRequest;
import com.nexachat.core.network.dto.LoginRequest;
import com.nexachat.core.network.dto.LogoutRequest;
import com.nexachat.core.network.dto.RegisterRequest;
import com.nexachat.core.storage.TokenStore;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;
import kotlin.Unit;
import kotlin.coroutines.Continuation;

/**
 * Auth boundary for the rest of the app: speaks Retrofit to {@code /api/v1/auth/…},
 * persists sessions in {@link TokenStore}, and translates the server error envelope
 * into {@link ApiError}. Phone numbers are normalized to E.164 (region VN)
 * best-effort — the server re-validates anyway, so a parse failure sends raw.
 *
 * <p>The public coroutine API is declared by {@link AuthRepositoryApi};
 * {@link AuthRepositoryFlow} keeps suspend work in Kotlin while this class owns
 * request construction and outcome mapping.
 */
@Singleton
public final class AuthRepository implements AuthRepositoryApi {

    private final AuthApi authApi;
    private final TokenStore tokenStore;
    private final SessionEvents sessionEvents;
    private final TokenRefresher tokenRefresher;

    @Inject
    public AuthRepository(
            @NonNull AuthApi authApi,
            @NonNull TokenStore tokenStore,
            @NonNull SessionEvents sessionEvents,
            @NonNull TokenRefresher tokenRefresher) {
        this.authApi = Objects.requireNonNull(authApi, "authApi");
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
        this.sessionEvents = Objects.requireNonNull(sessionEvents, "sessionEvents");
        this.tokenRefresher = Objects.requireNonNull(tokenRefresher, "tokenRefresher");
    }

    @Override
    public Object register(
            @NonNull String phone,
            @NonNull String password,
            @NonNull String displayName,
            @NonNull Continuation<? super Result<AuthResponse>> completion) {
        return AuthRepositoryFlow.register(this, phone, password, displayName, completion);
    }

    @Override
    public Object login(
            @NonNull String phone,
            @NonNull String password,
            @NonNull Continuation<? super Result<AuthResponse>> completion) {
        return AuthRepositoryFlow.login(this, phone, password, completion);
    }

    /**
     * Rotates the refresh token via {@link TokenRefresher} — the SAME mutex the
     * OkHttp authenticator uses, so splash-restore can never race a 401-triggered
     * rotation into a TOKEN_REUSED family revocation.
     */
    @Override
    public Object refresh(@NonNull Continuation<? super Result<Boolean>> completion) {
        return AuthRepositoryFlow.refresh(this, completion);
    }

    /**
     * Best-effort server-side revocation; the local session is always cleared
     * and a {@link SessionEvents.Event.LoggedOut} is broadcast (drives global
     * nav → Login).
     */
    @Override
    public Object logout(@NonNull Continuation<? super Result<Unit>> completion) {
        return AuthRepositoryFlow.logout(this, completion);
    }

    /**
     * FR-A4 "logout-all": revokes every refresh token server-side, then the
     * same local clear + global nav as {@link #logout}. Bearer-auth, so no body.
     */
    @Override
    public Object logoutAll(@NonNull Continuation<? super Result<Unit>> completion) {
        return AuthRepositoryFlow.logoutAll(this, completion);
    }

    @Override
    public Object changePassword(
            @NonNull String oldPassword,
            @NonNull String newPassword,
            @NonNull Continuation<? super Result<Unit>> completion) {
        return AuthRepositoryFlow.changePassword(this, oldPassword, newPassword, completion);
    }

    @NonNull
    AuthApi authApi() {
        return authApi;
    }

    @NonNull
    TokenStore tokenStore() {
        return tokenStore;
    }

    @NonNull
    SessionEvents sessionEvents() {
        return sessionEvents;
    }

    @NonNull
    TokenRefresher tokenRefresher() {
        return tokenRefresher;
    }

    @NonNull
    RegisterRequest registerRequest(
            @NonNull String phone,
            @NonNull String password,
            @NonNull String displayName) {
        return new RegisterRequest(
                PhoneNormalizer.toE164(phone), password, WhitespaceTrim.trim(displayName));
    }

    @NonNull
    LoginRequest loginRequest(@NonNull String phone, @NonNull String password) {
        return new LoginRequest(PhoneNormalizer.toE164(phone), password);
    }

    @NonNull
    LogoutRequest logoutRequest(@NonNull String refreshToken) {
        return new LogoutRequest(refreshToken);
    }

    /**
     * Passing the live refresh token keeps THIS session; the server revokes
     * every OTHER device instead of kicking the user out right after.
     */
    @NonNull
    ChangePasswordRequest changePasswordRequest(
            @NonNull String oldPassword,
            @NonNull String newPassword,
            @Nullable String keepToken) {
        return new ChangePasswordRequest(oldPassword, newPassword, keepToken);
    }

    /**
     * Splash-restore mapping: {@code Success} = refreshed (the refresher already
     * persisted and handled rejection side-effects); {@link ApiError.Network} for
     * transient failures (caller may keep the session) vs any other error for a
     * dead session (caller should drop to Login).
     */
    @NonNull
    Result<Boolean> refreshMapping(@NonNull TokenRefreshOutcome outcome) {
        if (outcome instanceof TokenRefreshOutcome.FreshToken) {
            return new Result.Success<>(Boolean.TRUE);
        }
        if (outcome instanceof TokenRefreshOutcome.TransientFailure) {
            return Result.failure(new ApiError.Network("refresh request failed"));
        }
        if (outcome instanceof TokenRefreshOutcome.Rejected) {
            return Result.failure(new ApiError.Unknown("SESSION_REJECTED"));
        }
        return Result.failure(new ApiError.Unknown("NO_REFRESH_TOKEN"));
    }
}
