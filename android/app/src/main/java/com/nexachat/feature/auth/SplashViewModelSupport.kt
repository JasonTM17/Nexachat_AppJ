package com.nexachat.feature.auth

import com.nexachat.core.storage.TokenStore
import com.nexachat.data.AuthRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.rx3.rxSingle

/** Channel helpers and suspend restore for the Java [SplashViewModel]. */
internal fun newSplashNavChannel(): Channel<SplashNavEvent> = Channel(Channel.BUFFERED)

internal fun splashNavEvents(channel: Channel<SplashNavEvent>): Flow<SplashNavEvent> =
    channel.receiveAsFlow()

internal suspend fun splashEmitRestoreDestination(
    tokenStore: TokenStore,
    authRepository: AuthRepository,
    events: Channel<SplashNavEvent>,
) {
    // Temporary bridge: keep the coroutine -> Rx adaptation at the call site.
    coroutineScope {
        val destination = SplashSession.restoreDestination(
            rxSingle { tokenStore.hasValidRefresh.first() },
            rxSingle { authRepository.refresh() },
        ).await()
        events.send(destination)
    }
}
