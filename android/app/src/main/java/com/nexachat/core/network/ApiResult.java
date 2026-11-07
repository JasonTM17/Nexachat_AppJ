package com.nexachat.core.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.network.dto.ErrorResponse;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.io.IOException;
import java.util.Objects;
import retrofit2.Call;
import retrofit2.Response;

/**
 * Network-layer result carrying HTTP specifics (status + parsed server {@code code}).
 * Repositories translate it into domain {@code Result}/{@code ApiError}.
 *
 * <p>Error variants keep a type parameter so success and failure branches
 * retain the same {@code ApiResult<T>} type.
 */
public sealed interface ApiResult<T>
        permits ApiResult.Success, ApiResult.HttpError, ApiResult.NetworkError {

    /**
     * Executes the Retrofit call on RxJava's IO scheduler, cloning it first if it
     * has already executed. Disposing the subscription cancels the request. A
     * successful empty response is represented by {@code Success(null)}; callers
     * that require a body must check it.
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
                    emitter.onSuccess(new NetworkError<>(failure));
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
            return new Success<>(response.body());
        }
        String raw = response.errorBody() == null ? null : response.errorBody().string();
        return new HttpError<>(response.code(), ErrorResponse.errorCodeOf(raw));
    }

    final class Success<T> implements ApiResult<T> {
        private final T body;

        public Success(T body) {
            this.body = body;
        }

        public T getBody() {
            return body;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Success<?> success)) {
                return false;
            }
            return Objects.equals(body, success.body);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(body);
        }

        @Override
        public String toString() {
            return "Success(body=" + body + ")";
        }
    }

    /** Non-2xx; {@code errorCode} is the server's {@code error.code} when parseable. */
    final class HttpError<T> implements ApiResult<T> {
        private final int status;
        @Nullable private final String errorCode;

        public HttpError(int status, @Nullable String errorCode) {
            this.status = status;
            this.errorCode = errorCode;
        }

        public int getStatus() {
            return status;
        }

        @Nullable
        public String getErrorCode() {
            return errorCode;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof HttpError<?> httpError)) {
                return false;
            }
            return status == httpError.status && Objects.equals(errorCode, httpError.errorCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(status, errorCode);
        }

        @Override
        public String toString() {
            return "HttpError(status=" + status + ", errorCode=" + errorCode + ")";
        }
    }

    /** No HTTP response: DNS, timeout, offline. */
    final class NetworkError<T> implements ApiResult<T> {
        private final IOException cause;

        public NetworkError(IOException cause) {
            this.cause = Objects.requireNonNull(cause, "cause");
        }

        public IOException getCause() {
            return cause;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof NetworkError<?> networkError)) {
                return false;
            }
            return Objects.equals(cause, networkError.cause);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(cause);
        }

        @Override
        public String toString() {
            return "NetworkError(cause=" + cause + ")";
        }
    }
}
