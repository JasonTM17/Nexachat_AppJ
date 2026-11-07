package com.nexachat.core.network;

import androidx.annotation.NonNull;
import com.nexachat.core.network.dto.ErrorResponse;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import retrofit2.Call;
import retrofit2.Response;

/** Static Java facade preserving the former Kotlin top-level JVM class name. */
public final class ApiResultKt {

    private ApiResultKt() {
    }

    /**
     * Executes a clone of {@code call} on RxJava's IO scheduler. Disposing the
     * subscription cancels the request. A successful empty response is represented
     * by {@code Success(null)}; callers that require a body must check it.
     */
    @NonNull
    public static <T> Single<ApiResult<T>> safeApiCall(@NonNull Call<T> call) {
        return Single.<ApiResult<T>>create(emitter -> {
            Call<T> cloned = call.isExecuted() ? call.clone() : null;
            Call<T> active = cloned != null ? cloned : call;
            emitter.setCancellable(active::cancel);
            try {
                emitter.onSuccess(fromResponse(active.execute()));
            } catch (IOException failure) {
                if (!emitter.isDisposed()) {
                    emitter.onSuccess(new ApiResult.NetworkError<>(failure));
                }
            } catch (RuntimeException failure) {
                if (!emitter.isDisposed()) {
                    emitter.onError(failure);
                }
            }
        }).subscribeOn(Schedulers.io());
    }

    private static <T> ApiResult<T> fromResponse(Response<T> response) throws IOException {
        if (response.isSuccessful()) {
            return new ApiResult.Success<>(response.body());
        }
        String raw = response.errorBody() == null ? null : response.errorBody().string();
        return new ApiResult.HttpError<>(response.code(), ErrorResponse.errorCodeOf(raw));
    }
}
