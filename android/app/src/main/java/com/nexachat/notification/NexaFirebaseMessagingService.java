package com.nexachat.notification;

import android.content.Context;
import android.os.PowerManager;
import androidx.annotation.NonNull;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import dagger.hilt.EntryPoint;
import dagger.hilt.InstallIn;
import dagger.hilt.android.EntryPointAccessors;
import dagger.hilt.components.SingletonComponent;

/**
 * Firebase Cloud Messaging service handling incoming lightweight push notifications.
 * Processes pointers (&lt;500B), acquires a 15-second partial wake lock to keep the CPU awake,
 * and delegates processing to {@link FcmMessageProcessor}.
 */
public class NexaFirebaseMessagingService extends FirebaseMessagingService {

    private static final String WAKE_LOCK_TAG = "nexachat:fcm_wake_lock";
    private static final long WAKE_LOCK_TIMEOUT_MS = 15_000L;

    @EntryPoint
    @InstallIn(SingletonComponent.class)
    public interface FcmServiceEntryPoint {
        FcmMessageProcessor fcmMessageProcessor();
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wakeLock = null;
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
            wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        }

        try {
            FcmMessageProcessor processor = resolveProcessor(this);
            if (processor != null && remoteMessage.getData() != null) {
                processor.processDataPayload(remoteMessage.getData());
            }
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        }
    }

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        // Token refresh handling if required
    }

    private static FcmMessageProcessor resolveProcessor(Context context) {
        try {
            FcmServiceEntryPoint entryPoint = EntryPointAccessors.fromApplication(
                    context.getApplicationContext(), FcmServiceEntryPoint.class);
            return entryPoint.fcmMessageProcessor();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
