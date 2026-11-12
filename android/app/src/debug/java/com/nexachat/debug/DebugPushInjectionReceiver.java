package com.nexachat.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import com.nexachat.notification.FcmMessageProcessor;
import com.nexachat.notification.NexaFirebaseMessagingService;
import dagger.hilt.android.EntryPointAccessors;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Debug-only entry for an injected FCM data map. Manifest registration lives in
 * {@code src/debug} and is not merged into release or localSmoke.
 *
 * <p>The payload is handed to {@link FcmMessageProcessor#processDataPayload}, the same
 * method {@code NexaFirebaseMessagingService.onMessageReceived} calls. This class does
 * not interpret NEW_MESSAGE, REMINDER, or CALL_INVITE.
 */
public final class DebugPushInjectionReceiver extends BroadcastReceiver {
    public static final String ACTION_INJECT = "com.nexachat.debug.INJECT_PUSH";
    static final String WAKE_LOCK_TAG = "nexachat:debug_push_injection";
    /** Same budget as the FCM service wake lock, used as a backstop if finish is delayed. */
    static final long WAKE_LOCK_TIMEOUT_MS = 15_000L;
    /**
     * Keeps the cold-started process at broadcast priority while the processor's
     * asynchronous admission runs. Short of the historical 10-second receiver budget.
     */
    static final long COLD_START_HOLD_MS = 8_000L;
    /**
     * Platform uids. The {@code Process} fields that name them were added in API 29;
     * the numeric values are older than minSdk 26, so the debug receiver uses the numbers.
     */
    static final int SHELL_UID = 2000;
    static final int ROOT_UID = 0;
    static final int INVALID_UID = -1;
    private static final String TAG = "DebugPushInjection";
    private static final int SENDER_UID_API = 34;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        int senderUid = INVALID_UID;
        if (Build.VERSION.SDK_INT >= SENDER_UID_API) {
            senderUid = getSentFromUid();
        }
        if (!isAllowedSender(senderUid, Build.VERSION.SDK_INT)) {
            Log.i(TAG, "Rejected debug push injection; sender uid " + senderUid);
            return;
        }
        if (!ACTION_INJECT.equals(intent.getAction())) {
            Log.i(TAG, "Rejected debug push injection; action was not INJECT_PUSH");
            return;
        }
        Map<String, String> payload = payloadFromExtras(bundleToMap(intent.getExtras()));
        if (payload.isEmpty()) {
            Log.i(TAG, "Rejected debug push injection; empty payload");
            return;
        }
        PendingResult pending = goAsync();
        PowerManager.WakeLock wakeLock = acquireWakeLock(context);
        try {
            FcmMessageProcessor processor = resolveProcessor(context);
            if (processor == null) {
                Log.i(TAG, "Debug push injection could not resolve FcmMessageProcessor");
            } else {
                processor.processDataPayload(payload);
            }
        } catch (RuntimeException failed) {
            Log.i(TAG, "Debug push injection failed: " + failed.getClass().getSimpleName());
        } finally {
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> finishDelivery(wakeLock, pending), COLD_START_HOLD_MS);
        }
    }

    /**
     * Shell and root only, and only on API 34+ where {@link #getSentFromUid()} exists.
     * Older platforms fail closed because the sender uid cannot be read.
     */
    @VisibleForTesting
    static boolean isAllowedSender(int senderUid, int sdkInt) {
        if (sdkInt < SENDER_UID_API) {
            return false;
        }
        return senderUid == SHELL_UID || senderUid == ROOT_UID;
    }

    /**
     * Copies string extras into the map shape of a Firebase data message.
     * Framework {@code android.*} extras and non-strings are dropped so they cannot
     * become payload fields.
     */
    @VisibleForTesting
    static Map<String, String> payloadFromExtras(@Nullable Map<String, ?> extras) {
        if (extras == null || extras.isEmpty()) {
            return Map.of();
        }
        Map<String, String> payload = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : extras.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.startsWith("android.")) {
                continue;
            }
            if (entry.getValue() instanceof String text) {
                payload.put(key, text);
            }
        }
        return Collections.unmodifiableMap(payload);
    }

    @Nullable
    private static Map<String, Object> bundleToMap(@Nullable Bundle extras) {
        if (extras == null || extras.isEmpty()) {
            return null;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (String key : extras.keySet()) {
            String text = extras.getString(key);
            if (text != null) {
                values.put(key, text);
            }
        }
        return values;
    }

    @Nullable
    private static FcmMessageProcessor resolveProcessor(Context context) {
        try {
            NexaFirebaseMessagingService.FcmServiceEntryPoint entryPoint =
                    EntryPointAccessors.fromApplication(
                            context.getApplicationContext(),
                            NexaFirebaseMessagingService.FcmServiceEntryPoint.class);
            return entryPoint.fcmMessageProcessor();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    @Nullable
    private static PowerManager.WakeLock acquireWakeLock(Context context) {
        PowerManager powerManager = context.getSystemService(PowerManager.class);
        if (powerManager == null) {
            return null;
        }
        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        return wakeLock;
    }

    private static void finishDelivery(
            @Nullable PowerManager.WakeLock wakeLock, PendingResult pending) {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (RuntimeException ignored) {
            // The acquire timeout may already have released it.
        }
        pending.finish();
    }
}
