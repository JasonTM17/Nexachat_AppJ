package com.nexachat.feature.auth

import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.storage.TokenStore
import com.nexachat.data.AuthRepository
import kotlinx.coroutines.flow.first

/** Suspend session-restore decision used by [SplashViewModel]. */
internal object SplashSession {

    @JvmStatic
    suspend fun restoreDestination(
        tokenStore: TokenStore,
        authRepository: AuthRepository,
    ): SplashNavEvent {
        if (!tokenStore.hasValidRefresh.first()) {
            return SplashNavEvent.ToLogin.INSTANCE
        }
        return when (val result = authRepository.refresh()) {
            is Result.Success -> SplashNavEvent.ToInbox.INSTANCE
            is Result.Failure ->
                if (result.error is ApiError.Network) {
                    SplashNavEvent.ToInbox.INSTANCE
                } else {
                    SplashNavEvent.ToLogin.INSTANCE
                }
        }
    }
}
