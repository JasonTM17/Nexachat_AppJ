package com.nexachat;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;
import com.nexachat.call.core.CallManager;
import com.nexachat.call.service.CallNotificationObserver;
import com.nexachat.core.network.stomp.RealtimeSession;
import com.nexachat.core.realtime.ephemeral.EphemeralStateRouter;
import dagger.hilt.android.HiltAndroidApp;
import javax.inject.Inject;

/**
 * Application entry point; Hilt-generated component attaches here; modules under {@code core/} and {@code data/} supply bindings.
 */
@HiltAndroidApp
public final class NexaChatApp extends Application {

    @Inject
    RealtimeSession realtimeSession;

    @Inject
    EphemeralStateRouter ephemeralStateRouter;

    @Inject
    CallManager callManager;

    @Override
    public void onCreate() {
        super.onCreate();
        new CallNotificationObserver(this, callManager).start();
        ProcessLifecycleOwner.get().getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onStart(@NonNull LifecycleOwner owner) {
                realtimeSession.onAppForeground();
                ephemeralStateRouter.onAppForeground();
            }

            @Override
            public void onStop(@NonNull LifecycleOwner owner) {
                ephemeralStateRouter.onAppBackground();
            }
        });
    }
}
