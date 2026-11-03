package com.nexachat.core.network

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * OkHttp 401 recovery — delegates the actual rotation to [TokenRefresher], the
 * single flight path shared with `AuthRepository.refresh()` (splash restore).
 *
 * Guards kept locally because they are request-level concerns:
 *  - public-path 401s (login/register/refresh itself) are real answers — bail
 *    BEFORE touching the refresh path, which also prevents re-entering the
 *    refresher mutex from the refresh call's own 401;
 *  - [responseCount] caps chained retries at [MAX_ATTEMPTS].
 *
 * Outcomes: `FreshToken` → retry the request with it; `Rejected`/`NoRefreshToken`
 * → give up (session dead, user will be routed to Login by SessionEvents);
 * `TransientFailure` → give up this request but keep the tokens.
 */
@Singleton
class TokenAuthenticator @Inject constructor(
    private val tokenRefresher: TokenRefresher,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val failedRequest = response.request

        if (AuthApi.isPublicAuthPath(failedRequest.url.encodedPath)) return null
        if (responseCount(response) >= MAX_ATTEMPTS) return null

        val failedAccessToken = failedRequest.bearerToken()

        return runBlocking {
            when (val outcome = tokenRefresher.refreshIfNeeded(failedAccessToken)) {
                is TokenRefresher.Outcome.FreshToken ->
                    failedRequest.retryWith(outcome.accessToken)
                else -> null
            }
        }
    }

    private fun Request.retryWith(accessToken: String): Request =
        newBuilder()
            .header(AuthInterceptor.HEADER_AUTHORIZATION, AuthInterceptor.BEARER_PREFIX + accessToken)
            .build()

    private fun Request.bearerToken(): String? =
        header(AuthInterceptor.HEADER_AUTHORIZATION)
            ?.removePrefix(AuthInterceptor.BEARER_PREFIX)

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
