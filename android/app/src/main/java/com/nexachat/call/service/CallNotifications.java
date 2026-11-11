package com.nexachat.call.service;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.nexachat.R;
import com.nexachat.call.CallLauncher;
import com.nexachat.call.core.CallSession;
import com.nexachat.call.core.CallState;
import com.nexachat.call.core.IncomingCallPushData;
import com.nexachat.call.ui.CallActivity;
import com.nexachat.call.ui.CallNotificationActionRouter;
import com.nexachat.call.ui.CallNotificationPrivacyPolicy;
import com.nexachat.call.ui.IncomingCallIntentCodec;
import com.nexachat.call.ui.IncomingCallActivity;
import com.nexachat.core.common.AppLocaleManager;

/** Builds incoming and ongoing call notifications on the user-visible calls channel. */
public final class CallNotifications {
    public static final String CHANNEL_ID = "calls";
    public static final String ONGOING_CHANNEL_ID = "ongoing_calls";
    public static final int INCOMING_NOTIFICATION_ID = 4701;
    public static final int ONGOING_NOTIFICATION_ID = 4702;

    private static final long[] VIBRATION_PATTERN = {0L, 700L, 500L, 700L, 1800L};

    private CallNotifications() { }

    public static boolean showIncoming(Context context, CallSession session) {
        return showIncoming(context, session, null);
    }

    public static boolean showIncoming(
            Context context,
            CallSession session,
            @Nullable IncomingCallPushData pushData) {
        if (context == null || session == null
                || (session.getState() != CallState.INCOMING_RINGING
                && session.getState() != CallState.PENDING_INCOMING)) {
            return false;
        }
        Context appContext = localizedAppContext(context);
        if (pushData != null && (!pushData.matches(session)
                || !pushData.isValidAt(System.currentTimeMillis()))) {
            clearIncoming(appContext);
            return false;
        }
        ensureChannel(appContext);
        if (!canPost(appContext, CHANNEL_ID)) return false;
        Intent openIntent = incomingActivityIntent(
                appContext, session, CallNotificationActionRouter.ACTION_OPEN, pushData);
        Intent acceptIntent = incomingActivityIntent(
                appContext, session, CallNotificationActionRouter.ACTION_ACCEPT, pushData);
        PendingIntent open = PendingIntent.getActivity(
                appContext, requestCode(session, CallNotificationActionRouter.ACTION_OPEN), openIntent,
                pendingIntentFlags());
        PendingIntent accept = PendingIntent.getActivity(
                appContext, requestCode(session, CallNotificationActionRouter.ACTION_ACCEPT), acceptIntent,
                pendingIntentFlags());
        PendingIntent decline = PendingIntent.getService(
                appContext, requestCode(session, CallNotificationActionRouter.ACTION_DECLINE),
                serviceIntent(appContext, CallNotificationActionRouter.ACTION_DECLINE, session, pushData),
                pendingIntentFlags());

        boolean canUseFullScreen = true;
        if (Build.VERSION.SDK_INT >= 34) {
            NotificationManager nm = (NotificationManager) appContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                canUseFullScreen = nm.canUseFullScreenIntent();
            }
        }

        String peerName = displayName(appContext, session);
        CallNotificationPrivacyPolicy.Content copy = CallNotificationPrivacyPolicy.incoming(
                peerName, appContext.getString(R.string.call_notification_incoming));
        NotificationCompat.Builder builder = new NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle(copy.getPrivateTitle())
                .setContentText(copy.getPrivateText())
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion(appContext, CHANNEL_ID, copy))
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(open)
                .addAction(android.R.drawable.sym_action_call,
                        appContext.getString(R.string.call_notification_accept), accept)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        appContext.getString(R.string.call_notification_decline), decline);
        if (canUseFullScreen) {
            builder.setFullScreenIntent(open, true);
        } else {
            builder.setPriority(NotificationCompat.PRIORITY_MAX);
        }
        return notifySafely(appContext, INCOMING_NOTIFICATION_ID, builder.build());
    }

    public static void clearIncoming(Context context) {
        if (context == null) return;
        NotificationManagerCompat.from(context.getApplicationContext())
                .cancel(INCOMING_NOTIFICATION_ID);
    }

    public static android.app.Notification buildOngoing(Context context, CallSession session) {
        Context appContext = localizedAppContext(context);
        ensureOngoingChannel(appContext);
        String peerName = session == null ? appContext.getString(R.string.call_peer_fallback)
                : displayName(appContext, session);
        String status = statusFor(appContext, session);
        CallNotificationPrivacyPolicy.Content copy = CallNotificationPrivacyPolicy.ongoing(
                peerName, status, appContext.getString(R.string.call_notification_ongoing));
        Intent openIntent = new Intent(appContext, CallActivity.class)
                .putExtra(CallLauncher.EXTRA_CONVERSATION_ID,
                        session == null ? null : session.getConversationId())
                .putExtra(CallLauncher.EXTRA_PEER_USER_ID,
                        session == null ? null : session.getPeerUserId())
                .putExtra(CallLauncher.EXTRA_PEER_NAME,
                        session == null ? peerName : session.getPeerDisplayName())
                .putExtra(CallLauncher.EXTRA_PEER_PHONE, (String) null)
                .putExtra(com.nexachat.call.ui.IncomingCallActivity.EXTRA_CALL_ID, callId(session))
                .setAction(CallNotificationActionRouter.ACTION_OPEN);
        PendingIntent open = PendingIntent.getActivity(
                appContext, ONGOING_NOTIFICATION_ID, openIntent, pendingIntentFlags());
        String callId = session == null ? null : session.getCallId();
        PendingIntent hangUp = PendingIntent.getService(
                appContext, requestCode(session, CallNotificationActionRouter.ACTION_HANG_UP),
                serviceIntent(appContext, CallNotificationActionRouter.ACTION_HANG_UP, callId),
                pendingIntentFlags());
        return new NotificationCompat.Builder(appContext, ONGOING_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle(copy.getPrivateTitle())
                .setContentText(copy.getPrivateText())
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion(appContext, ONGOING_CHANNEL_ID, copy))
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        appContext.getString(R.string.call_notification_hang_up), hangUp)
                .build();
    }

    public static void showOngoing(Context context, CallSession session) {
        if (context == null) return;
        notifySafely(localizedAppContext(context), ONGOING_NOTIFICATION_ID,
                buildOngoing(context, session));
    }

    public static void clearOngoing(Context context) {
        if (context == null) return;
        NotificationManagerCompat.from(context.getApplicationContext())
                .cancel(ONGOING_NOTIFICATION_ID);
    }

    public static Intent incomingActivityIntent(Context context, CallSession session, String action) {
        return incomingActivityIntent(context, session, action, null);
    }

    public static Intent incomingActivityIntent(
            Context context,
            CallSession session,
            String action,
            @Nullable IncomingCallPushData pushData) {
        Intent intent = new Intent(context, IncomingCallActivity.class)
                .setAction(action)
                .putExtra(IncomingCallActivity.EXTRA_CALL_ID, session.getCallId());
        if (pushData != null && pushData.matches(session)) {
            IncomingCallIntentCodec.putPushData(intent, pushData);
        }
        return intent;
    }

    private static Intent serviceIntent(
            Context context,
            String action,
            CallSession session,
            @Nullable IncomingCallPushData pushData) {
        Intent intent = serviceIntent(context, action, session.getCallId());
        if (pushData != null && pushData.matches(session)) {
            IncomingCallIntentCodec.putPushData(intent, pushData);
        }
        return intent;
    }

    private static Intent serviceIntent(Context context, String action, String callId) {
        return new Intent(context, CallForegroundService.class)
                .setAction(action)
                .putExtra(IncomingCallActivity.EXTRA_CALL_ID, callId);
    }

    private static int requestCode(CallSession session, String action) {
        int callCode = session == null || session.getCallId() == null ? 0 : session.getCallId().hashCode();
        return 31 * callCode + action.hashCode();
    }

    private static int pendingIntentFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return flags;
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, context.getString(R.string.call_notification_channel),
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription(context.getString(R.string.call_notification_incoming));
        channel.enableVibration(true);
        channel.setVibrationPattern(VIBRATION_PATTERN);
        channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PRIVATE);
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        channel.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), audioAttributes);
        manager.createNotificationChannel(channel);
    }

    private static void ensureOngoingChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                ONGOING_CHANNEL_ID, context.getString(R.string.call_notification_ongoing_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(context.getString(R.string.call_notification_ongoing));
        channel.enableVibration(false);
        channel.setSound(null, null);
        channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(channel);
    }

    private static Context localizedAppContext(Context context) {
        return AppLocaleManager.wrapContext(context.getApplicationContext());
    }

    private static String displayName(Context context, CallSession session) {
        String name = session.getPeerDisplayName();
        return name == null || name.trim().isEmpty()
                ? context.getString(R.string.call_peer_fallback) : name;
    }

    private static String statusFor(Context context, CallSession session) {
        if (session == null) return context.getString(R.string.call_notification_ongoing);
        switch (session.getState()) {
            case OUTGOING_RINGING: return context.getString(R.string.call_state_calling);
            case CONNECTING: return context.getString(R.string.call_state_connecting);
            case ACTIVE: return context.getString(R.string.call_state_connected);
            default: return context.getString(R.string.call_notification_ongoing);
        }
    }

    private static android.app.Notification publicVersion(
            Context context,
            String channelId,
            CallNotificationPrivacyPolicy.Content copy) {
        return new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle(copy.getPublicTitle())
                .setContentText(copy.getPublicText())
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    private static boolean canPost(Context context, String channelId) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        NotificationManager manager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;
        NotificationChannel channel = manager.getNotificationChannel(channelId);
        return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    private static String callId(CallSession session) {
        return session == null ? null : session.getCallId();
    }

    private static boolean notifySafely(Context context, int id, android.app.Notification notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification);
            return true;
        } catch (SecurityException ignored) {
            // Android 13+ may suppress notifications until the user grants POST_NOTIFICATIONS.
            return false;
        }
    }
}
