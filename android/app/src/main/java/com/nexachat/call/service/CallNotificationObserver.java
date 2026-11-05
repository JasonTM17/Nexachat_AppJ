package com.nexachat.call.service;

import android.content.Context;
import android.content.Intent;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ProcessLifecycleOwner;
import com.nexachat.call.core.CallManager;
import com.nexachat.call.core.CallSession;
import com.nexachat.call.core.CallState;
import com.nexachat.call.ui.CallNotificationActionRouter;

/** Bridges the process-wide call manager to a foreground screen or an incoming call notification. */
public final class CallNotificationObserver implements CallManager.CallListener {
    private final Context appContext;
    private final CallManager callManager;
    private boolean started;

    public CallNotificationObserver(Context context, CallManager callManager) {
        appContext = context.getApplicationContext();
        this.callManager = callManager;
    }

    public synchronized void start() {
        if (started) return;
        started = true;
        callManager.addListener(this);
        onCallChanged(callManager.session().getValue());
    }

    public synchronized void stop() {
        if (!started) return;
        started = false;
        callManager.removeListener(this);
        CallNotifications.clearIncoming(appContext);
    }

    @Override
    public void onCallChanged(CallSession session) {
        if (session != null && session.getState() == CallState.INCOMING_RINGING) {
            if (isAppForeground()) {
                CallNotifications.clearIncoming(appContext);
                Intent intent = CallNotifications.incomingActivityIntent(
                        appContext, session, CallNotificationActionRouter.ACTION_OPEN)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                appContext.startActivity(intent);
            } else {
                CallNotifications.showIncoming(appContext, session);
            }
        } else {
            CallNotifications.clearIncoming(appContext);
        }
    }

    @Override
    public void onCallError(com.nexachat.call.core.CallError error) { }

    private static boolean isAppForeground() {
        return ProcessLifecycleOwner.get().getLifecycle().getCurrentState()
                .isAtLeast(Lifecycle.State.STARTED);
    }
}
