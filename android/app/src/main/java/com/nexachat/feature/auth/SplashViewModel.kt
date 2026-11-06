package com.nexachat.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexachat.core.common.ApiError
import com.nexachat.core.common.Result
import com.nexachat.core.storage.TokenStore
import com.nexachat.data.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Session-restore logic behind `SplashScreen` (phase-05 M1: "kill app → reopen
 * must not require login"). Emits exactly one [NavEvent]:
 *
 *  - no refresh token stored              → [NavEvent.ToLogin]
 *  - stored + `refresh()` succeeds         → [NavEvent.ToInbox] (new tokens saved)
 *  - stored + `refresh()` fails transient  → [NavEvent.ToInbox] — offline at
 *    launch keeps the session; the first real API call re-auths or logs out
 *  - stored + `refresh()` rejected (400/401/403) → [NavEvent.ToLogin]
 */
@HiltViewModel
class SplashViewModel @Inject constructor(
    private val tokenStore: TokenStore,
    private val authRepository: AuthRepository,
) : ViewModel() {

    sealed interface NavEvent {
        data object ToLogin : NavEvent
        data object ToInbox : NavEvent
    }

    private val _navEvents = Channel<NavEvent>(Channel.BUFFERED)
    val navEvents = _navEvents.receiveAsFlow()

    init {
        restoreSession()
    }

    private fun restoreSession() {
        viewModelScope.launch {
            val hasRefreshToken = tokenStore.hasValidRefresh.first()
            val destination = when {
                !hasRefreshToken -> NavEvent.ToLogin
                else -> when (val result = authRepository.refresh()) {
                    is Result.Success -> NavEvent.ToInbox
                    is Result.Failure ->
                        if (result.error is ApiError.Network) NavEvent.ToInbox
                        else NavEvent.ToLogin
                }
            }
            _navEvents.send(destination)
        }
    }
}
