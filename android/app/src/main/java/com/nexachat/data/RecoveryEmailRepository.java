package com.nexachat.data;

import androidx.annotation.NonNull;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.Result;
import com.nexachat.core.common.Unit;
import com.nexachat.core.network.ApiResult;
import com.nexachat.core.network.UserApi;
import com.nexachat.core.network.dto.SetRecoveryEmailRequest;
import com.nexachat.core.network.dto.VerifyRecoveryEmailRequest;
import com.nexachat.core.network.dto.VerifyRecoveryEmailResponse;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;
import retrofit2.Call;

/** Authenticated recovery-email OTP API; matches brief 306 server contract. */
@Singleton
public final class RecoveryEmailRepository {

    private final UserApi userApi;

    @Inject
    public RecoveryEmailRepository(@NonNull UserApi userApi) {
        this.userApi = userApi;
    }

    @NonNull
    public Single<Result<Unit>> requestVerificationCode(@NonNull String email) {
        String normalized = normalizeEmail(email);
        return callAccepted(userApi.requestRecoveryEmailVerification(new SetRecoveryEmailRequest(normalized)));
    }

    @NonNull
    public Single<Result<VerifyRecoveryEmailResponse>> verifyCode(@NonNull String code) {
        return call(userApi.verifyRecoveryEmail(new VerifyRecoveryEmailRequest(code.trim())));
    }

    @NonNull
    public Single<Result<Void>> removeVerifiedEmail() {
        return callNoContent(userApi.removeRecoveryEmail());
    }

    @NonNull
    static String normalizeEmail(@NonNull String raw) {
        return raw.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private Single<Result<Unit>> callAccepted(@NonNull Call<Void> call) {
        return ApiResult.safeApiCall(call)
                .subscribeOn(Schedulers.io())
                .map(result -> {
                    if (result instanceof ApiResult.Success<?>) {
                        return new Result.Success<>(Unit.INSTANCE);
                    }
                    if (result instanceof ApiResult.HttpError<?> httpError) {
                        return Result.<Unit>failure(mapRecoveryError(
                                httpError.getStatus(), httpError.getErrorCode()));
                    }
                    ApiResult.NetworkError<?> networkError = (ApiResult.NetworkError<?>) result;
                    return Result.<Unit>failure(
                            new ApiError.Network(networkError.getCause().getMessage()));
                });
    }

    private Single<Result<Void>> callNoContent(@NonNull Call<Void> call) {
        return ApiResult.safeApiCall(call)
                .subscribeOn(Schedulers.io())
                .map(result -> {
                    if (result instanceof ApiResult.Success<?>) {
                        return new Result.Success<Void>(null);
                    }
                    if (result instanceof ApiResult.HttpError<?> httpError) {
                        return Result.<Void>failure(mapRecoveryError(
                                httpError.getStatus(), httpError.getErrorCode()));
                    }
                    ApiResult.NetworkError<?> networkError = (ApiResult.NetworkError<?>) result;
                    return Result.<Void>failure(
                            new ApiError.Network(networkError.getCause().getMessage()));
                });
    }

    private <T> Single<Result<T>> call(@NonNull Call<T> call) {
        return ApiResult.safeApiCall(call)
                .subscribeOn(Schedulers.io())
                .map(result -> {
                    if (result instanceof ApiResult.Success<?> success) {
                        @SuppressWarnings("unchecked")
                        T body = (T) success.getBody();
                        if (body == null) {
                            return Result.<T>failure(new ApiError.Unknown("UNEXPECTED_EMPTY_BODY"));
                        }
                        return new Result.Success<>(body);
                    }
                    if (result instanceof ApiResult.HttpError<?> httpError) {
                        return Result.<T>failure(mapRecoveryError(
                                httpError.getStatus(), httpError.getErrorCode()));
                    }
                    ApiResult.NetworkError<?> networkError = (ApiResult.NetworkError<?>) result;
                    return Result.<T>failure(
                            new ApiError.Network(networkError.getCause().getMessage()));
                });
    }

    private static ApiError mapRecoveryError(int status, String code) {
        if (status == 503 && "MAIL_UNAVAILABLE".equals(code)) {
            return ApiError.MailUnavailable.INSTANCE;
        }
        if ("MAIL_UNAVAILABLE".equals(code)) {
            return ApiError.MailUnavailable.INSTANCE;
        }
        if ("INVALID_OR_EXPIRED_CODE".equals(code)) {
            return ApiError.InvalidOrExpiredCode.INSTANCE;
        }
        return ApiError.from(status, code);
    }
}
