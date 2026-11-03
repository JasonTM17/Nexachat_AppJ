package com.nexachat.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.shareIn

/**
 * Emits once per *validated* network regain. Exists for one reason: the WS
 * reconnect loop intentionally gives up after [ReconnectPolicy.MAX_ATTEMPTS]
 * failures (OFFLINE) — a network coming back is the only signal that can
 * re-arm it without waiting for the next user action.
 *
 * `NET_CAPABILITY_VALIDATED` filters captive-portal-style "connected but not
 * really" transitions; flap storms are harmless because the consumer
 * ([RealtimeSession.launchConnectionLoop]) self-dedups while a loop is alive.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext context: Context,
    scope: CoroutineScope,
) {
    /**
     * One shared [ConnectivityManager] registration for the app lifetime —
     * `Lazily` starts the upstream on the first collector and never stops it.
     *
     * This MUST be hot: the raw `callbackFlow` is cold, so every collector
     * would register its own callback — and Android delivers [onAvailable]
     * immediately for already-connected networks at registration. A collector
     * subscribing *later* (e.g. a connection loop parking while connectivity
     * is still up) would then receive a spurious "regain" instantly and wake
     * forever — retry-spinning instead of parking (verified on-device:
     * repeated fresh-collector emissions while the server was down).
     * `replay = 0` keeps that same guarantee for late subscribers: only real,
     * post-subscription regains are delivered.
     */
    val regained: SharedFlow<Unit> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(Unit)
            }
        }
        manager.registerNetworkCallback(request, callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.shareIn(scope, SharingStarted.Lazily, replay = 0)
}
