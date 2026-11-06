package com.nexachat.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexachat.core.storage.TokenStore
import com.nexachat.data.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Session-restore logic behind `SplashScreen` (phase-05 M1: "kill app → reopen
 * must not require login"). Emits exactly one [SplashNavEvent].
 */
@HiltViewModel
class SplashViewModel @Inject constructor(
    private val tokenStore: TokenStore,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _navEvents = Channel<SplashNavEvent>(Channel.BUFFERED)
    val navEvents = _navEvents.receiveAsFlow()

    init {
        viewModelScope.launch {
            _navEvents.send(SplashSession.restoreDestination(tokenStore, authRepository))
        }
    }
}
