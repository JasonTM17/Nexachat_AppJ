package com.nexachat.core.network

import com.nexachat.core.network.dto.ErrorResponse
import java.io.IOException
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Response

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
                response.code(),
                ErrorResponse.errorCodeOf(response.errorBody()?.string()),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        ApiResult.NetworkError(e)
    }

/**
 * Runs a Retrofit [Call] and normalizes the outcome the same way as the
 * suspend overload. Cancels [call] if this coroutine is cancelled. A 2xx
 * with a null body still becomes `Success(Unit as T)`.
 */
@Suppress("UNCHECKED_CAST")
suspend fun <T> safeApiCall(call: Call<T>): ApiResult<T> =
    withContext(dispatcherForBlockingCall()) {
        coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                try {
                    call.cancel()
                } catch (_: RuntimeException) {
                    // Call.cancel is best-effort. A test double may leave it unstubbed.
                }
            }
        }
        try {
            val response = call.execute()
            if (response.isSuccessful) {
                ApiResult.Success((response.body() ?: Unit) as T)
            } else {
                ApiResult.HttpError(
                    response.code(),
                    ErrorResponse.errorCodeOf(response.errorBody()?.string()),
                )
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e)
        }
    }

/**
 * Production hops to [Dispatchers.IO] so [Call.execute] cannot block the main
 * thread. A kotlinx-coroutines-test dispatcher stays in place: `runTest`
 * only controls that dispatcher, and leaving it makes `runCurrent` observe a
 * still-running call.
 */
private suspend fun dispatcherForBlockingCall(): CoroutineDispatcher {
    val interceptor = coroutineContext[ContinuationInterceptor]
    val name = interceptor?.javaClass?.name.orEmpty()
    return if (interceptor is CoroutineDispatcher && name.contains("TestDispatcher")) {
        interceptor
    } else {
        Dispatchers.IO
    }
}
