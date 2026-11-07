package com.nexachat.core.network

/** Single-flight refresh API. The mutex stays in [TokenRefresherCore]. */
interface TokenRefresherApi {
    suspend fun refresh(): TokenRefreshOutcome

    suspend fun refreshIfNeeded(failedAccessToken: String?): TokenRefreshOutcome
}
