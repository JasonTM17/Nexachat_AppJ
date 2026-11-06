package com.nexachat.core.network

import com.nexachat.core.network.dto.ErrorResponse
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import retrofit2.Response

/**
 * Network-layer result carrying HTTP specifics (status + parsed server `code`).
 * Repositories translate it into domain `Result`/`ApiError`.
 */
sealed interface ApiResult<out T> {
    data class Success<T>(val body: T) : ApiResult<T>

    /** Non-2xx; [errorCode] is the server's `error.code` when parseable. */
    data class HttpError(
        val status: Int,
        val errorCode: String?,
    ) : ApiResult<Nothing>

    /** No HTTP response: DNS, timeout, offline. */
    data class NetworkError(val cause: IOException) : ApiResult<Nothing>
}

/**
 * Runs a Retrofit suspend call and normalizes outcomes into [ApiResult].
 * A successful call with no body (204) yields `Success(Unit as T)` — safe for
 * the `Response<Unit>` endpoints used here. Cancellation is never swallowed.
 */
@Suppress("UNCHECKED_CAST")
suspend fun <T> safeApiCall(block: suspend () -> Response<T>): ApiResult<T> =
    try {
        val response = block()
        if (response.isSuccessful) {
            ApiResult.Success((response.body() ?: Unit) as T)
        } else {
            ApiResult.HttpError(
                status = response.code(),
                errorCode = ErrorResponse.errorCodeOf(response.errorBody()?.string()),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        ApiResult.NetworkError(e)
    }
