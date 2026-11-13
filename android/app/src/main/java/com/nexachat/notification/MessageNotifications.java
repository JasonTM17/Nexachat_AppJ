package com.nexachat.notification;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import com.nexachat.R;
import dagger.hilt.android.qualifiers.ApplicationContext;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Posts one privacy-safe, coalesced message alert per account and conversation. */
@Singleton
public final class MessageNotifications {
    private static final String CHANNEL_ID = "messages";
    private final Context context;

    @Inject
    public MessageNotifications(@ApplicationContext @NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean isEnabled() {
        ensureChannel();
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            NotificationChannel channel = manager == null ? null : manager.getNotificationChannel(CHANNEL_ID);
            return channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    public boolean show(@NonNull NotificationWorkRequest request) {
        if (!isEnabled()) return false;
        int notificationId = notificationId(request.getRecipientId(), request.getConversationId());
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_chat_bubble_outline)
                .setContentTitle(context.getString(R.string.message_notification_title))
                .setContentText(context.getString(R.string.message_notification_generic))
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(contentIntent(request, notificationId));
        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build());
            return true;
        } catch (SecurityException permissionRevoked) {
            return false;
        }
    }

    public static int notificationId(String recipientId, String conversationId) {
        return stableId("message:" + recipientId + ":" + conversationId);
    }

    public void dismissForSessionEnd() {
        dismissChannel(context, CHANNEL_ID);
    }

    /** Enumerate persisted alerts too; in-memory ids disappear on process death. */
    static void dismissChannel(Context context, String channelId) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        try {
            StatusBarNotification[] alerts = manager.getActiveNotifications();
            if (alerts == null) return;
            for (StatusBarNotification alert : alerts) {
                if (channelId.equals(alert.getNotification().getChannelId())) {
                    manager.cancel(alert.getTag(), alert.getId());
                }
            }
        } catch (SecurityException unavailable) {
            // Notification access must not prevent token cleanup or session navigation.
        }
    }

    private PendingIntent contentIntent(NotificationWorkRequest request, int requestCode) {
        Intent intent = new Intent(context, NotificationOpenActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        request.putInIntent(intent);
        return PendingIntent.getActivity(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static int stableId(String value) {
        int result = value.hashCode() & 0x7fffffff;
        return result == 0 ? 1 : result;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.message_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT));
    }
}
