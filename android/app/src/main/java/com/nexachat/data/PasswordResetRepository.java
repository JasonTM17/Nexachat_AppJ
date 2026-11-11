package com.nexachat.data;

import androidx.annotation.NonNull;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.common.PhoneNormalizer;
import com.nexachat.core.common.Result;
import com.nexachat.core.common.Unit;
import com.nexachat.core.network.ApiResult;
import com.nexachat.core.network.AuthApi;
import com.nexachat.core.network.dto.PasswordResetConfirmRequest;
import com.nexachat.core.network.dto.PasswordResetRequest;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;
import retrofit2.Call;

/** Public password-reset OTP API (brief 306 contract). */
@Singleton
public final class PasswordResetRepository {

    private final AuthApi authApi;

    @Inject
    public PasswordResetRepository(@NonNull AuthApi authApi) {
        this.authApi = Objects.requireNonNull(authApi, "authApi");
    }

    @NonNull
    public Single<Result<Unit>> requestResetCode(@NonNull String phone) {
        String normalized = PhoneNormalizer.toE164(phone);
        return callAccepted(authApi.requestPasswordReset(new PasswordResetRequest(normalized)));
    }

    @NonNull
    public Single<Result<Unit>> confirmReset(
            @NonNull String phone, @NonNull String code, @NonNull String newPassword) {
        String normalized = PhoneNormalizer.toE164(phone);
        return callNoContent(authApi.confirmPasswordReset(
                new PasswordResetConfirmRequest(normalized, code.trim(), newPassword)));
    }

    private Single<Result<Unit>> callAccepted(@NonNull Call<Void> call) {
        return ApiResult.safeApiCall(call)
                .subscribeOn(Schedulers.io())
                .map(result -> {
                    if (result instanceof ApiResult.Success<?>) {
                        return new Result.Success<>(Unit.INSTANCE);
                    }
                    if (result instanceof ApiResult.HttpError<?> httpError) {
                        return Result.<Unit>failure(mapResetError(
                                httpError.getStatus(), httpError.getErrorCode()));
                    }
                    ApiResult.NetworkError<?> networkError = (ApiResult.NetworkError<?>) result;
                    return Result.<Unit>failure(
                            new ApiError.Network(networkError.getCause().getMessage()));
                });
    }

    private Single<Result<Unit>> callNoContent(@NonNull Call<Void> call) {
        return ApiResult.safeApiCall(call)
                .subscribeOn(Schedulers.io())
                .map(result -> {
                    if (result instanceof ApiResult.Success<?>) {
                        return new Result.Success<>(Unit.INSTANCE);
                    }
                    if (result instanceof ApiResult.HttpError<?> httpError) {
                        return Result.<Unit>failure(mapResetError(
                                httpError.getStatus(), httpError.getErrorCode()));
                    }
                    ApiResult.NetworkError<?> networkError = (ApiResult.NetworkError<?>) result;
                    return Result.<Unit>failure(
                            new ApiError.Network(networkError.getCause().getMessage()));
                });
    }

    static ApiError mapResetError(int status, String code) {
        if ("INVALID_OR_EXPIRED_CODE".equals(code)) {
            return ApiError.InvalidOrExpiredCode.INSTANCE;
        }
        return ApiError.from(status, code);
    }
}
