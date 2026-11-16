package com.nexachat;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;
import com.nexachat.call.core.CallManager;
import com.nexachat.call.service.CallNotificationObserver;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.network.stomp.RealtimeSession;
import com.nexachat.core.realtime.ephemeral.EphemeralStateRouter;
import dagger.hilt.android.HiltAndroidApp;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.inject.Inject;

/**
 * Application entry point; Hilt-generated component attaches here; modules under {@code core/} and {@code data/} supply bindings.
 */
@HiltAndroidApp
public final class NexaChatApp extends Application {

    private static final Logger LOGGER = Logger.getLogger("NexaChatApp");

    @Inject
    RealtimeSession realtimeSession;

    @Inject
    TokenRefresher tokenRefresher;

    @Inject
    EphemeralStateRouter ephemeralStateRouter;

    @Inject
    CallManager callManager;

    @Override
    public void onCreate() {
        super.onCreate();
        Schedulers.io().scheduleDirect(() -> {
            try {
                tokenRefresher.warmSessionAdmission();
            } catch (Throwable warmFailure) {
                LOGGER.log(Level.WARNING,
                        "session admission warm-up failed; fenced paths will bootstrap lazily",
                        warmFailure);
            }
        });
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
