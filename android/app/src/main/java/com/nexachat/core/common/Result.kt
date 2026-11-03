package com.nexachat.core.common

/**
 * Minimal result type for repository APIs: either a value or a typed
 * [ApiError]. Distinct from `kotlin.Result` because failures must carry the
 * parsed error-envelope code, not a Throwable.
 */
sealed interface Result<out T> {
    data class Success<out T>(val value: T) : Result<T>
    data class Failure(val error: ApiError) : Result<Nothing>

    fun getOrNull(): T? = (this as? Success)?.value

    fun onSuccess(block: (T) -> Unit): Result<T> {
        if (this is Success) block(value)
        return this
    }

    fun onFailure(block: (ApiError) -> Unit): Result<T> {
        if (this is Failure) block(error)
        return this
    }
}
