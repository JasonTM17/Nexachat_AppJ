package com.nexachat.core.common;

import androidx.annotation.Nullable;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Repository result: a value or a typed {@link ApiError}. Distinct from
 * Typed outcome wrapper carrying the parsed error-envelope code on failure.
 */
public sealed interface Result<T> permits Result.Success, Result.Failure {

    final class Success<T> implements Result<T> {
        private final T value;

        public Success(T value) {
            this.value = value;
        }

        public T getValue() {
            return value;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Success<?> success)) {
                return false;
            }
            return Objects.equals(value, success.value);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(value);
        }

        @Override
        public String toString() {
            return "Success(value=" + value + ")";
        }
    }

    final class Failure<T> implements Result<T> {
        private final ApiError error;

        public Failure(ApiError error) {
            this.error = Objects.requireNonNull(error, "error");
        }

        public ApiError getError() {
            return error;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Failure<?> failure)) {
                return false;
            }
            return error.equals(failure.error);
        }

        @Override
        public int hashCode() {
            return error.hashCode();
        }

        @Override
        public String toString() {
            return "Failure(error=" + error + ")";
        }
    }

    /**
     * Failure whose type comes from the caller. The constructor's type argument
     * is not stored in the value, so the caller supplies the result type.
     */
    static <T> Result<T> failure(ApiError error) {
        return new Failure<>(error);
    }

    /**
     * Re-types a failure after a successful branch changed {@code T}.
     * Safe only for {@link Failure}: it carries no value of {@code T}.
     */
    @SuppressWarnings("unchecked")
    static <T> Result<T> cast(Result<?> result) {
        return (Result<T>) result;
    }

    @Nullable
    default T getOrNull() {
        if (this instanceof Success<?> success) {
            @SuppressWarnings("unchecked")
            T value = (T) success.getValue();
            return value;
        }
        return null;
    }

    default Result<T> onSuccess(Consumer<T> block) {
        Objects.requireNonNull(block, "block");
        if (this instanceof Success<?> success) {
            @SuppressWarnings("unchecked")
            T value = (T) success.getValue();
            block.accept(value);
        }
        return this;
    }

    default Result<T> onFailure(Consumer<ApiError> block) {
        Objects.requireNonNull(block, "block");
        if (this instanceof Failure<?> failure) {
            block.accept(failure.getError());
        }
        return this;
    }
}
