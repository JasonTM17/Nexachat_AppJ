package com.nexachat.data

import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.network.ApiResult
import com.nexachat.core.network.safeApiCall
import com.nexachat.core.network.dto.AuthResponse
import com.nexachat.core.storage.TokenStore
import retrofit2.Call

/**
 * Kotlin coroutine boundary for [AuthRepository]: Retrofit calls, token
 * persistence and session clearing stay suspend here while the Java class owns
 * request construction and outcome mapping.
 */
internal object AuthRepositoryFlow {

    @JvmStatic
    suspend fun register(
        repository: AuthRepository,
        phone: String,
        password: String,
        displayName: String,
    ): Result<AuthResponse> {
        val result = authCall(
            repository.authApi().register(
                repository.registerRequest(phone, password, displayName),
            ),
        )
        if (result is Result.Success) {
            persist(repository.tokenStore(), result.value)
        }
        return result
    }

    @JvmStatic
    suspend fun login(
        repository: AuthRepository,
        phone: String,
        password: String,
    ): Result<AuthResponse> {
        val result = authCall(
            repository.authApi().login(repository.loginRequest(phone, password)),
        )
        if (result is Result.Success) {
            persist(repository.tokenStore(), result.value)
        }
        return result
    }

    @JvmStatic
    suspend fun refresh(repository: AuthRepository): Result<Boolean> =
        repository.refreshMapping(repository.tokenRefresher().refresh())

    @JvmStatic
    suspend fun logout(repository: AuthRepository): Result<Unit> {
        val refreshToken = repository.tokenStore().readTokens()?.refreshToken
        if (refreshToken != null) {
            safeApiCall(repository.authApi().logout(repository.logoutRequest(refreshToken)))
        }
        repository.tokenStore().clear()
        repository.sessionEvents().emitLoggedOut()
        return Result.Success(Unit)
    }

    @JvmStatic
    suspend fun logoutAll(repository: AuthRepository): Result<Unit> {
        safeApiCall(repository.authApi().logoutAll())
        repository.tokenStore().clear()
        repository.sessionEvents().emitLoggedOut()
        return Result.Success(Unit)
    }

    @JvmStatic
    suspend fun changePassword(
        repository: AuthRepository,
        oldPassword: String,
        newPassword: String,
    ): Result<Unit> {
        val keepToken = repository.tokenStore().readTokens()?.refreshToken
        return authCallUnit(
            repository.authApi().changePassword(
                repository.changePasswordRequest(oldPassword, newPassword, keepToken),
            ),
        )
    }

    private suspend fun persist(tokenStore: TokenStore, auth: AuthResponse) {
        tokenStore.saveTokens(
            access = auth.accessToken,
            accessExpiresAtEpochMs = System.currentTimeMillis() +
                auth.accessTokenExpiresIn * 1_000L,
            refresh = auth.refreshToken,
        )
    }

    @JvmStatic
    suspend fun <T> authCall(call: Call<T>): Result<T> =
        when (val result = safeApiCallCatching(call)) {
            is ApiResult.Success -> Result.Success(result.body)
            is ApiResult.HttpError ->
                Result.failure(ApiError.from(result.status, result.errorCode))
            is ApiResult.NetworkError ->
                Result.failure(ApiError.Network(result.cause.message))
        }

    /** Empty Retrofit {@code Call<Void>} success maps directly to the app's Unit result. */
    @JvmStatic
    suspend fun authCallUnit(call: Call<Void>): Result<Unit> =
        when (val result = safeApiCallCatching(call)) {
            is ApiResult.Success -> Result.Success(Unit)
            is ApiResult.HttpError ->
                Result.failure(ApiError.from(result.status, result.errorCode))
            is ApiResult.NetworkError ->
                Result.failure(ApiError.Network(result.cause.message))
        }

    /**
     * safeApiCall casts an empty 2xx body to `Unit as T` — an erased cast that
     * only blows up when `Success.body` is later read as a non-Unit type.
     * Convert that ClassCastException into a domain failure instead of a crash.
     */
    @JvmStatic
    suspend fun <T> safeApiCallCatching(call: Call<T>): ApiResult<T> =
        try {
            safeApiCall(call)
        } catch (_: ClassCastException) {
            ApiResult.HttpError(200, "UNEXPECTED_EMPTY_BODY")
        }
}
