package com.nexachat.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.PhoneNormalizer;
import com.nexachat.core.common.Result;
import com.nexachat.core.common.WhitespaceTrim;
import com.nexachat.core.network.ApiResult;
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
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Objects;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;
import com.nexachat.core.common.Unit;
import retrofit2.Call;

/**
 * Auth boundary for the rest of the app: speaks Retrofit to {@code /api/v1/auth/…},
 * persists sessions in {@link TokenStore}, and translates the server error envelope
 * into {@link ApiError}. Phone numbers are normalized to E.164 (region VN)
 * best-effort — the server re-validates anyway, so a parse failure sends raw.
 *
 * <p>Network and token-store work runs on RxJava's IO scheduler. The returned
 * {@link Single}s are cold, so callers own the subscription and its disposal.
 */
@Singleton
public final class AuthRepository {

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

    @NonNull
    public Single<Result<AuthResponse>> register(
            @NonNull String phone,
            @NonNull String password,
            @NonNull String displayName) {
        RegisterRequest request = registerRequest(phone, password, displayName);
        return Single.defer(() -> persistOnSuccess(authCall(authApi.register(request))));
    }

    @NonNull
    public Single<Result<AuthResponse>> login(
            @NonNull String phone,
            @NonNull String password) {
        LoginRequest request = loginRequest(phone, password);
        return Single.defer(() -> persistOnSuccess(authCall(authApi.login(request))));
    }

    /**
     * Rotates the refresh token via {@link TokenRefresher} — the SAME mutex the
     * OkHttp authenticator uses, so splash-restore can never race a 401-triggered
     * rotation into a TOKEN_REUSED family revocation.
     */
    @NonNull
    public Single<Result<Boolean>> refresh() {
        return Single.defer(() -> tokenRefresher.refresh().map(this::refreshMapping))
                .subscribeOn(Schedulers.io());
    }

    /**
     * Best-effort server-side revocation; the local session is always cleared
     * and a {@link SessionEvents.Event.LoggedOut} is broadcast (drives global
     * nav → Login).
     */
    @NonNull
    public Single<Result<Unit>> logout() {
        Completable revoke = tokenStore.readTokens()
                .flatMapCompletable(tokens -> ApiResult.safeApiCall(
                                authApi.logout(logoutRequest(tokens.getRefreshToken())))
                        .ignoreElement())
                .onErrorComplete();
        return revoke
                .andThen(tokenStore.clear())
                .andThen(Completable.fromAction(sessionEvents::emitLoggedOut))
                .andThen(Single.<Result<Unit>>fromCallable(
                        () -> new Result.Success<>(Unit.INSTANCE)))
                .subscribeOn(Schedulers.io());
    }

    /**
     * FR-A4 "logout-all": revokes every refresh token server-side, then the
     * same local clear + global nav as {@link #logout}. Bearer-auth, so no body.
     */
    @NonNull
    public Single<Result<Unit>> logoutAll() {
        return Single.defer(() -> ApiResult.safeApiCall(authApi.logoutAll())
                        .ignoreElement()
                        .onErrorComplete()
                        .andThen(tokenStore.clear())
                        .andThen(Completable.fromAction(sessionEvents::emitLoggedOut))
                        .andThen(Single.<Result<Unit>>fromCallable(
                                () -> new Result.Success<>(Unit.INSTANCE))))
                .subscribeOn(Schedulers.io());
    }

    @NonNull
    public Single<Result<Unit>> changePassword(
            @NonNull String oldPassword,
            @NonNull String newPassword) {
        return tokenStore.readTokens()
                .map(tokens -> Optional.of(tokens.getRefreshToken()))
                .defaultIfEmpty(Optional.empty())
                .flatMap(keepToken -> authCallUnit(authApi.changePassword(
                        changePasswordRequest(oldPassword, newPassword, keepToken.orElse(null)))))
                .subscribeOn(Schedulers.io());
    }

    @NonNull
    private Single<Result<AuthResponse>> persistOnSuccess(
            Single<Result<AuthResponse>> request) {
        return request.flatMap(result -> {
            if (!(result instanceof Result.Success<?> success)) {
                return Single.just(result);
            }
            AuthResponse response = (AuthResponse) success.getValue();
            return tokenStore.saveTokens(
                            response.getAccessToken(),
                            System.currentTimeMillis() + response.getAccessTokenExpiresIn() * 1_000L,
                            response.getRefreshToken())
                    .andThen(Single.just(result));
        }).subscribeOn(Schedulers.io());
    }

    @NonNull
    private Single<Result<AuthResponse>> authCall(@NonNull Call<AuthResponse> call) {
        return ApiResult.safeApiCall(call)
                .map(AuthRepository::authResult)
                .subscribeOn(Schedulers.io());
    }

    @NonNull
    private Single<Result<Unit>> authCallUnit(@NonNull Call<Void> call) {
        return ApiResult.safeApiCall(call)
                .map(result -> {
                    if (result instanceof ApiResult.Success<?>) {
                        // Do not read the erased empty body from a successful 204 response.
                        return new Result.Success<Unit>(Unit.INSTANCE);
                    }
                    return Result.<Unit>cast(authResultFailure(result));
                })
                .subscribeOn(Schedulers.io());
    }

    @NonNull
    private static Result<AuthResponse> authResult(@NonNull ApiResult<AuthResponse> result) {
        if (result instanceof ApiResult.Success<?> success) {
            return new Result.Success<>((AuthResponse) success.getBody());
        }
        return authResultFailure(result);
    }

    @NonNull
    private static Result.Failure<AuthResponse> authResultFailure(
            @NonNull ApiResult<?> result) {
        if (result instanceof ApiResult.HttpError<?> httpError) {
            return new Result.Failure<>(ApiError.from(
                    httpError.getStatus(), httpError.getErrorCode()));
        }
        ApiResult.NetworkError<?> networkError = (ApiResult.NetworkError<?>) result;
        return new Result.Failure<>(new ApiError.Network(networkError.getCause().getMessage()));
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
