package com.nexachat.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

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
) {
    val regained: Flow<Unit> = callbackFlow {
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
    }
}
