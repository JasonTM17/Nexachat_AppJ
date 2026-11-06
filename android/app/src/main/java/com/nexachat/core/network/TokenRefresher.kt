package com.nexachat.core.network

import com.nexachat.core.network.dto.AuthResponse
import com.nexachat.core.network.dto.RefreshRequest
import com.nexachat.core.storage.TokenStore
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * THE single flight path for refresh-token rotation (plan §12.2).
 *
 * Two callers used to race: `TokenAuthenticator` (401 recovery) had a mutex, but
 * `AuthRepository.refresh()` (splash session restore) called `/auth/refresh`
 * outside it. Call A rotates RT1→RT2 while call B still presents revoked RT1 →
 * server `TOKEN_REUSED` → whole token family revoked → legit user force-logged-out.
 *
 * Now every rotation — 401-recovery or proactive — serializes through [mutex]:
 *  - [refreshIfNeeded] (401 path) skips the network call entirely when a sibling
 *    already rotated the access token while we waited on the lock;
 *  - [refresh] (splash path) always rotates, but still under the same lock.
 *
 * Rejection side-effects (wipe [TokenStore], broadcast [SessionEvents.LoggedOut])
 * live here so both callers can never forget them.
 *
 * [authApi] is a [Provider] because OkHttpClient → refresher → AuthApi →
 * Retrofit → OkHttpClient is a cycle; the provider resolves lazily on first use.
 */
@Singleton
class TokenRefresher @Inject constructor(
    private val tokenStore: TokenStore,
    private val sessionEvents: SessionEvents,
    private val authApi: Provider<AuthApi>,
) {

    sealed interface Outcome {
        /** A usable access token exists — just rotated or already fresh. */
        data class FreshToken(val accessToken: String) : Outcome

        /** 400/401/403 — the refresh token itself was rejected; tokens already wiped + LoggedOut emitted. */
        data object Rejected : Outcome

        /** 408/429/5xx/IO — tokens kept; caller may retry later. */
        data object TransientFailure : Outcome

        /** Nothing stored to refresh with. */
        data object NoRefreshToken : Outcome
    }

    private val mutex = Mutex()

    /**
     * Forced rotation for session restore at launch. Serialized with the 401
     * path so a concurrent 401 can't present the token we're about to revoke.
     */
    suspend fun refresh(): Outcome = mutex.withLock {
        val refreshToken = tokenStore.readTokens()?.refreshToken
            ?: return Outcome.NoRefreshToken
        performRefresh(refreshToken)
    }

    /**
     * 401-recovery: if a sibling request already rotated while we waited on the
     * lock, the stored access token differs from the one the server rejected —
     * reuse it without a second network call.
     */
    suspend fun refreshIfNeeded(failedAccessToken: String?): Outcome = mutex.withLock {
        val tokens = tokenStore.readTokens() ?: return Outcome.NoRefreshToken
        if (failedAccessToken != null && tokens.accessToken != failedAccessToken) {
            return Outcome.FreshToken(tokens.accessToken)
        }
        performRefresh(tokens.refreshToken)
    }

    private suspend fun performRefresh(refreshToken: String): Outcome =
        try {
            val response = authApi.get().refresh(RefreshRequest(refreshToken))
            val body = response.body()
            when {
                response.isSuccessful && body != null -> {
                    tokenStore.saveTokens(
                        access = body.accessToken,
                        accessExpiresAtEpochMs = System.currentTimeMillis() +
                            body.accessTokenExpiresIn * 1_000L,
                        refresh = body.refreshToken,
                    )
                    Outcome.FreshToken(body.accessToken)
                }
                isDefinitiveRejection(response.code()) -> {
                    // 400 validation, 401 TOKEN_INVALID/EXPIRED/REUSED, 403 forbidden.
                    // 408 and 429 are not session death: the refresh token is still valid.
                    tokenStore.clear()
                    sessionEvents.emitLoggedOut()
                    Outcome.Rejected
                }
                else -> Outcome.TransientFailure
            }
        } catch (_: IOException) {
            Outcome.TransientFailure
        }

    /** Contract rejections that mean this refresh token cannot be retried. */
    private fun isDefinitiveRejection(status: Int): Boolean =
        status == 400 || status == 401 || status == 403
}
