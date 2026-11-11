package com.nexachat.call.service;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.nexachat.call.core.CallManager;
import com.nexachat.call.core.CallSession;
import com.nexachat.call.core.CallState;
import com.nexachat.call.core.IncomingCallPushAdmission;
import com.nexachat.call.core.IncomingCallPushData;
import com.nexachat.call.ui.CallNotificationActionRouter;
import com.nexachat.call.ui.IncomingCallIntentCodec;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import dagger.hilt.android.AndroidEntryPoint;
import javax.inject.Inject;

/** Keeps an accepted or outgoing microphone call visible with a hang-up notification action. */
@AndroidEntryPoint
public class CallForegroundService extends Service {
    public static final String ACTION_START = "com.nexachat.call.START";
    public static final String ACTION_STOP = "com.nexachat.call.STOP";

    @Inject CallManager callManager;
    @Inject IncomingCallPushAdmission incomingCallPushAdmission;

    private boolean foregroundStarted;
    private CallManager.CallListener listener;

    public static void startForCall(Context context) {
        Intent intent = new Intent(context, CallForegroundService.class).setAction(ACTION_START);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, CallForegroundService.class).setAction(ACTION_STOP));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        listener = new CallManager.CallListener() {
            @Override public void onCallChanged(CallSession session) {
                onSessionChanged(session);
            }

            @Override public void onCallError(com.nexachat.call.core.CallError error) { }
        };
        callManager.addListener(listener);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (CallNotificationActionRouter.ACTION_HANG_UP.equals(action)) {
            String requestedCallId = intent.getStringExtra(
                    com.nexachat.call.ui.IncomingCallActivity.EXTRA_CALL_ID);
            CallSession session = callManager.session().getValue();
            if (session != null && session.getCallId() != null
                    && session.getCallId().equals(requestedCallId)) {
                callManager.hangUp();
            } else {
                retainLiveCallOrStop(startId);
            }
            return START_NOT_STICKY;
        }
        if (CallNotificationActionRouter.ACTION_DECLINE.equals(action)) {
            String requestedCallId = intent.getStringExtra(
                    com.nexachat.call.ui.IncomingCallActivity.EXTRA_CALL_ID);
            IncomingCallPushData pushData = IncomingCallIntentCodec.readPushData(intent);
            if (pushData != null) {
                if (!pushData.getCallId().equals(requestedCallId)) {
                    discardIncomingDecline(startId);
                    return START_NOT_STICKY;
                }
                incomingCallPushAdmission.admitAndRunIfCurrent(
                        pushData, () -> declineAdmittedCall(requestedCallId))
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(declined -> {
                            if (!declined) {
                                discardIncomingDecline(startId);
                            }
                        }, ignored -> discardIncomingDecline(startId),
                                () -> discardIncomingDecline(startId));
                return START_NOT_STICKY;
            }
            CallSession session = callManager.currentSessionSnapshot();
            if (session != null && requestedCallId != null
                    && requestedCallId.equals(session.getCallId())
                    && session.getDirection() == CallSession.Direction.INCOMING
                    && (session.getState() == CallState.INCOMING_RINGING
                        || session.getState() == CallState.PENDING_INCOMING)) {
                CallNotifications.clearIncoming(this);
                callManager.reject(requestedCallId);
            } else {
                discardIncomingDecline(startId);
            }
            return START_NOT_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        startForegroundForCurrentCall();
        CallSession session = callManager.session().getValue();
        if (session != null) onSessionChanged(session);
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (listener != null) callManager.removeListener(listener);
        if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @SuppressLint("InlinedApi")
    private void startForegroundForCurrentCall() {
        if (foregroundStarted) return;
        CallSession session = callManager.session().getValue();
        android.app.Notification notification = CallNotifications.buildOngoing(this, session);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(CallNotifications.ONGOING_NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(CallNotifications.ONGOING_NOTIFICATION_ID, notification);
        }
        foregroundStarted = true;
    }

    private void onSessionChanged(CallSession session) {
        if (session == null) return;
        if (session.getState() == CallState.IDLE) return;
        if (session.getState() == CallState.ENDED) {
            CallNotifications.clearIncoming(this);
            CallNotifications.clearOngoing(this);
            if (foregroundStarted) {
                stopForeground(STOP_FOREGROUND_REMOVE);
                foregroundStarted = false;
            }
            stopSelf();
            return;
        }
        if (foregroundStarted) CallNotifications.showOngoing(this, session);
    }

    private void retainLiveCallOrStop(int startId) {
        CallSession session = callManager.session().getValue();
        if (foregroundStarted && session != null
                && session.getState() != CallState.IDLE && session.getState() != CallState.ENDED) {
            CallNotifications.showOngoing(this, session);
        } else {
            stopSelf(startId);
        }
    }

    private void declineAdmittedCall(String callId) {
        CallNotifications.clearIncoming(this);
        callManager.reject(callId);
    }

    private void discardIncomingDecline(int startId) {
        CallNotifications.clearIncoming(this);
        stopSelf(startId);
    }
}
