package com.nexachat.core.network.stomp

import com.nexachat.core.network.AppForegroundState
import com.nexachat.core.network.NetworkMonitor
import com.nexachat.core.network.TokenRefreshOutcome
import com.nexachat.core.network.TokenRefresher
import com.nexachat.core.storage.TokenStore
import com.nexachat.core.storage.Tokens
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.rx3.asFlow
import kotlinx.coroutines.rx3.await
import kotlinx.coroutines.rx3.awaitSingleOrNull

/**
 * Kotlin-only coroutine boundary for [RealtimeSession]. The session API,
 * mutable state, STOMP policy callbacks, and event decoding live in Java;
 * this bridge keeps suspend calls and multi-flow collection structured.
 */
internal object RealtimeSessionFlow {

    @JvmStatic
    fun <T> stateView(state: MutableStateFlow<T>): StateFlow<T> = state.asStateFlow()

    @JvmStatic
    fun <T> sharedView(flow: MutableSharedFlow<T>): SharedFlow<T> = flow.asSharedFlow()

    interface ConnectionCallbacks {
        fun onConnectionState(state: StompClient.State)

        fun onConnectionEnded()

        fun onOffline()
    }

    @JvmStatic
    fun observeTokens(
        scope: CoroutineScope,
        tokenStore: TokenStore,
        onTokenPresence: Consumer<Boolean>,
    ): Job = scope.launch {
        tokenStore.hasValidRefresh().toObservable().asFlow().collect { hasToken -> onTokenPresence.accept(hasToken) }
    }

    @JvmStatic
    fun observeNetworkRegain(
        scope: CoroutineScope,
        tokenStore: TokenStore,
        networkMonitor: NetworkMonitor,
        isOpen: BooleanSupplier,
        onRegain: Runnable,
    ): Job = scope.launch {
        networkMonitor.regained.collect {
            if (!isOpen.getAsBoolean() && tokenStore.hasValidRefresh().toObservable().asFlow().first()) {
                onRegain.run()
            }
        }
    }

    @JvmStatic
    fun rearmOnForeground(
        scope: CoroutineScope,
        tokenStore: TokenStore,
        isOpen: BooleanSupplier,
        onForeground: Runnable,
    ): Job = scope.launch {
        if (!isOpen.getAsBoolean() && tokenStore.hasValidRefresh().toObservable().asFlow().first()) {
            onForeground.run()
        }
    }

    @JvmStatic
    fun collectFrames(
        scope: CoroutineScope,
        frames: SharedFlow<StompFrame>,
        onFrame: Consumer<StompFrame>,
    ): Job = scope.launch {
        frames.collect { frame -> onFrame.accept(frame) }
    }

    @JvmStatic
    fun launchConnectionLoop(
        scope: CoroutineScope,
        tokenStore: TokenStore,
        tokenRefresher: TokenRefresher,
        stompClient: StompClient,
        reconnectPolicy: ReconnectPolicy,
        networkMonitor: NetworkMonitor,
        foreground: AppForegroundState,
        rearmRequests: SharedFlow<Unit>,
        callbacks: ConnectionCallbacks,
    ): Job = scope.launch {
        var failures = 0
        var parkedRounds = 0
        while (currentCoroutineContext().isActive) {
            // STOMP CONNECT is authenticated after the HTTP upgrade. An expired
            // JWT is rejected without a stable auth ERROR frame, so refresh it
            // from the locally stored expiry before opening a socket.
            val tokens: Tokens? = tokenStore.readTokens().awaitSingleOrNull()
            if (tokens != null && tokens.isAccessExpired()) {
                when (tokenRefresher.refreshIfNeeded(tokens.accessToken)) {
                    // A fresh token, or a transient outage: the attempt below
                    // is the honest test — HTTP may rotate or the socket may fail.
                    is TokenRefreshOutcome.FreshToken,
                    is TokenRefreshOutcome.TransientFailure,
                    -> Unit

                    // A definitive rejection has cleared the session. A socket
                    // attempt can only fail, so let logout own the next action.
                    is TokenRefreshOutcome.Rejected,
                    is TokenRefreshOutcome.NoRefreshToken,
                    -> return@launch
                }
            }

            stompClient.connect().collect { state ->
                callbacks.onConnectionState(state)
                when (state) {
                    is StompClient.State.Open -> {
                        failures = 0
                        parkedRounds = 0
                    }
                    is StompClient.State.Failed -> {
                        if (state.auth) {
                            // Rotate for a 401/403 handshake so the NEXT attempt
                            // authenticates with a fresh access token.
                            tokenRefresher.refresh()
                        }
                    }
                    else -> Unit
                }
            }
            callbacks.onConnectionEnded()
            failures++
            if (!reconnectPolicy.shouldRetry(failures)) {
                callbacks.onOffline()
                parkedRounds++
                parkUntilRearmed(
                    parkedRounds,
                    tokenStore,
                    networkMonitor,
                    foreground,
                    rearmRequests,
                )
                failures = 0
                continue
            }
            delay(reconnectPolicy.delayMillis(failures))
        }
    }

    private suspend fun parkUntilRearmed(
        round: Int,
        tokenStore: TokenStore,
        networkMonitor: NetworkMonitor,
        foreground: AppForegroundState,
        rearmRequests: SharedFlow<Unit>,
    ) {
        while (currentCoroutineContext().isActive) {
            val retryWhileParked =
                foreground.isForeground.first() && tokenStore.hasValidRefresh().toObservable().asFlow().first()
            if (!retryWhileParked) {
                merge(networkMonitor.regained, rearmRequests).first()
                return
            }
            val wakeUps = merge(
                networkMonitor.regained.map { ParkWake.SIGNAL },
                rearmRequests.map { ParkWake.SIGNAL },
                // Ignore StateFlow's replayed current value; only transitions
                // should restart the foreground retry timer.
                foreground.isForeground.drop(1).map { ParkWake.FOREGROUND },
            )
            when (withTimeoutOrNull(parkedRetryDelay(round)) { wakeUps.first() }) {
                ParkWake.SIGNAL -> return
                ParkWake.FOREGROUND -> Unit
                null -> return
            }
        }
    }

    private fun parkedRetryDelay(round: Int): Long {
        val shift = (round - 1).coerceIn(0, MAX_PARKED_SHIFT)
        return (RealtimeSession.PARKED_RETRY_BASE_MS shl shift)
            .coerceAtMost(RealtimeSession.PARKED_RETRY_MAX_MS)
    }

    private enum class ParkWake { SIGNAL, FOREGROUND }

    private const val MAX_PARKED_SHIFT = 4
}
