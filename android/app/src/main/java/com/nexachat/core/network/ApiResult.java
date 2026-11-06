package com.nexachat.core.network;

import androidx.annotation.Nullable;
import java.io.IOException;
import java.util.Objects;

/**
 * Network-layer result carrying HTTP specifics (status + parsed server {@code code}).
 * Repositories translate it into domain {@code Result}/{@code ApiError}.
 *
 * <p>Error variants keep a type parameter (like {@code Result.Failure}) so a
 * {@code safeApiCallCatching} helper can return {@code ApiResult<T>} without
 * Kotlin {@code Nothing} covariance.
 */
public sealed interface ApiResult<T>
        permits ApiResult.Success, ApiResult.HttpError, ApiResult.NetworkError {

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
