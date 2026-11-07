package com.nexachat.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexachat.core.storage.TokenStore
import com.nexachat.data.AuthRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Channel and session restore for the Java [SplashViewModel]. */
internal fun newSplashNavChannel(): Channel<SplashNavEvent> = Channel(Channel.BUFFERED)

internal fun splashNavEvents(channel: Channel<SplashNavEvent>): Flow<SplashNavEvent> =
    channel.receiveAsFlow()

internal fun startSplashRestore(
    viewModel: ViewModel,
    tokenStore: TokenStore,
    authRepository: AuthRepository,
    events: Channel<SplashNavEvent>,
) {
    viewModel.viewModelScope.launch {
        events.send(SplashSession.restoreDestination(tokenStore, authRepository))
    }
}
