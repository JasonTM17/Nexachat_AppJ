package com.nexachat.feature.auth

import com.nexachat.core.storage.TokenStore
import com.nexachat.data.AuthRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** Channel helpers and suspend restore for the Java [SplashViewModel]. */
internal fun newSplashNavChannel(): Channel<SplashNavEvent> = Channel(Channel.BUFFERED)

internal fun splashNavEvents(channel: Channel<SplashNavEvent>): Flow<SplashNavEvent> =
    channel.receiveAsFlow()

internal suspend fun splashEmitRestoreDestination(
    tokenStore: TokenStore,
    authRepository: AuthRepository,
    events: Channel<SplashNavEvent>,
) {
    events.send(SplashSession.restoreDestination(tokenStore, authRepository))
}
