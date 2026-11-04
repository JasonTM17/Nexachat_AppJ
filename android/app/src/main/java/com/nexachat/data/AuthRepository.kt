package com.nexachat.data

import com.nexachat.core.common.ApiError
import com.nexachat.core.common.PhoneNormalizer
import com.nexachat.core.common.Result
import com.nexachat.core.network.ApiResult
import com.nexachat.core.network.AuthApi
import com.nexachat.core.network.SessionEvents
import com.nexachat.core.network.TokenRefresher
import com.nexachat.core.network.dto.AuthResponse
import com.nexachat.core.network.dto.ChangePasswordRequest
import com.nexachat.core.network.dto.LoginRequest
import com.nexachat.core.network.dto.LogoutRequest
import com.nexachat.core.network.dto.RegisterRequest
import com.nexachat.core.network.safeApiCall
import com.nexachat.core.storage.TokenStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auth boundary for the rest of the app: speaks Retrofit to `/api/v1/auth/…`,
 * persists sessions in [TokenStore], and translates the server error envelope
 * into [ApiError]. Phone numbers are normalized to E.164 (region VN)
 * best-effort — the server re-validates anyway, so a parse failure sends raw.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val authApi: AuthApi,
    private val tokenStore: TokenStore,
    private val sessionEvents: SessionEvents,
    private val tokenRefresher: TokenRefresher,
) {

    suspend fun register(phone: String, password: String, displayName: String): Result<AuthResponse> =
        authCall {
            authApi.register(
                RegisterRequest(
                    phone = PhoneNormalizer.toE164(phone),
                    password = password,
                    displayName = displayName.trim(),
                ),
            )
        }.also { result -> (result as? Result.Success)?.let { persist(it.value) } }

    suspend fun login(phone: String, password: String): Result<AuthResponse> =
        authCall {
            authApi.login(LoginRequest(phone = PhoneNormalizer.toE164(phone), password = password))
        }.also { result -> (result as? Result.Success)?.let { persist(it.value) } }

    /**
     * Rotates the refresh token via [TokenRefresher] — the SAME mutex the OkHttp
     * authenticator uses, so splash-restore can never race a 401-triggered
     * rotation into a TOKEN_REUSED family revocation.
     * `Success` = refreshed (refresher already persisted + handles rejection
     * side-effects); `Failure` carries [ApiError.Network] for transient
     * failures (caller may keep the session) vs any other error for a dead
     * session (caller should drop to Login).
     */
    suspend fun refresh(): Result<Boolean> =
        when (tokenRefresher.refresh()) {
            is TokenRefresher.Outcome.FreshToken -> Result.Success(true)
            TokenRefresher.Outcome.TransientFailure ->
                Result.Failure(ApiError.Network("refresh request failed"))
            TokenRefresher.Outcome.Rejected ->
                Result.Failure(ApiError.Unknown("SESSION_REJECTED"))
            TokenRefresher.Outcome.NoRefreshToken ->
                Result.Failure(ApiError.Unknown("NO_REFRESH_TOKEN"))
        }

    /**
     * Best-effort server-side revocation; the local session is always cleared
     * and a [SessionEvents.LoggedOut] is broadcast (drives global nav → Login).
     */
    suspend fun logout(): Result<Unit> {
        val refreshToken = tokenStore.readTokens()?.refreshToken
        if (refreshToken != null) {
            safeApiCall { authApi.logout(LogoutRequest(refreshToken)) } // ignore outcome
        }
        tokenStore.clear()
        sessionEvents.emitLoggedOut()
        return Result.Success(Unit)
    }

    /**
     * FR-A4 "logout-all": revokes every refresh token server-side, then the
     * same local clear + global nav as [logout]. Bearer-auth, so no body.
     */
    suspend fun logoutAll(): Result<Unit> {
        safeApiCall { authApi.logoutAll() } // ignore outcome
        tokenStore.clear()
        sessionEvents.emitLoggedOut()
        return Result.Success(Unit)
    }

    suspend fun changePassword(oldPassword: String, newPassword: String): Result<Unit> {
        // Passing the live refresh token keeps THIS session; the server revokes
        // every OTHER device instead of kicking the user out right after.
        val keepToken = tokenStore.readTokens()?.refreshToken
        return authCall {
            authApi.changePassword(
                ChangePasswordRequest(
                    oldPassword = oldPassword,
                    newPassword = newPassword,
                    refreshToken = keepToken,
                ),
            )
        }
    }

    // ---- internals ----

    private suspend fun persist(auth: AuthResponse) {
        tokenStore.saveTokens(
            access = auth.accessToken,
            accessExpiresAtEpochMs = System.currentTimeMillis() +
                auth.accessTokenExpiresIn * 1_000L,
            refresh = auth.refreshToken,
        )
    }

    private suspend fun <T> authCall(block: suspend () -> retrofit2.Response<T>): Result<T> =
        when (val result = safeApiCallCatching(block)) {
            is ApiResult.Success -> Result.Success(result.body)
            is ApiResult.HttpError ->
                Result.Failure(ApiError.from(result.status, result.errorCode))
            is ApiResult.NetworkError ->
                Result.Failure(ApiError.Network(result.cause.message))
        }

    /**
     * safeApiCall casts an empty 2xx body to `Unit as T` — an erased cast that
     * only blows up when `Success.body` is later read as a non-Unit type.
     * Convert that ClassCastException into a domain failure instead of a crash.
     */
    private suspend fun <T> safeApiCallCatching(
        block: suspend () -> retrofit2.Response<T>,
    ): ApiResult<T> =
        try {
            safeApiCall(block)
        } catch (_: ClassCastException) {
            ApiResult.HttpError(status = 200, errorCode = "UNEXPECTED_EMPTY_BODY")
        }
}
