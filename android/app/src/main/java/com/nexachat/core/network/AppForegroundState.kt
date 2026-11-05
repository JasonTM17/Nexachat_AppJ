package com.nexachat.core.network

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn

/**
 * Process-level foreground flag — one value for the whole app, independent of
 * how many activities are alive.
 *
 * Exists for the parked reconnect loop: retrying a dead backend is only worth
 * battery while the app is on screen, so [com.nexachat.core.network.stomp.RealtimeSession]
 * gates its periodic re-arm on this signal. An interface (not a concrete
 * lifecycle owner) so the connection loop stays JVM-testable.
 */
interface AppForegroundState {
    /** `true` between process onStart and onStop; hot, conflated, distinct. */
    val isForeground: Flow<Boolean>
}

/**
 * [AppForegroundState] backed by `ProcessLifecycleOwner` (lifecycle-process is
 * already on the app's classpath and initialises itself via App Startup).
 *
 * The reporter is only ever registered/removed on the main thread —
 * `LifecycleRegistry` enforces that — hence `flowOn(Main.immediate)`.
 */
@Singleton
class ProcessForegroundState @Inject constructor() : AppForegroundState {

    override val isForeground: Flow<Boolean> = callbackFlow {
        val owner = ProcessLifecycleOwner.get()
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                trySend(true)
            }

            override fun onStop(owner: LifecycleOwner) {
                trySend(false)
            }
        }
        owner.lifecycle.addObserver(observer)
        trySend(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        awaitClose { owner.lifecycle.removeObserver(observer) }
    }
        .conflate()
        .distinctUntilChanged()
        .flowOn(Dispatchers.Main.immediate)
}
