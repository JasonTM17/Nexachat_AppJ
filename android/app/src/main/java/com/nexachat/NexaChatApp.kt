package com.nexachat

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.nexachat.call.core.CallManager
import com.nexachat.call.service.CallNotificationObserver
import com.nexachat.core.network.stomp.RealtimeSession
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point. Hilt's generated [Hilt_NexaChatApp] component lives
 * here; modules under `core/` and `data/` contribute bindings.
 */
@HiltAndroidApp
class NexaChatApp : Application() {

    @Inject
    lateinit var realtimeSession: RealtimeSession

    @Inject
    lateinit var callManager: CallManager

    override fun onCreate() {
        super.onCreate()
        CallNotificationObserver(this, callManager).start()
        // Foreground re-arm: a socket that died while the app was
        // backgrounded (idle TCP close, doze, server restart) reconnects on
        // resume — connectivity never toggled, so the regain callback can't.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = realtimeSession.onAppForeground()
        })
    }
}
