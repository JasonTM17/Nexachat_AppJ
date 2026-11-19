package com.nexachat.notification;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import com.nexachat.R;
import dagger.hilt.android.qualifiers.ApplicationContext;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Posts the once-a-day streak reminder on the shared reminders channel. */
@Singleton
public final class StreakNotifications {
    private static final String CHANNEL_ID = "reminders";

    private final Context context;

    @Inject
    public StreakNotifications(@ApplicationContext @NonNull Context context) {
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

    public boolean show(
            @NonNull String recipientId,
            long sessionEpoch,
            @NonNull String conversationId,
            @Nullable String peerName,
            int count) {
        if (!isEnabled()) return false;
        NotificationWorkRequest request =
                NotificationWorkRequest.streakReminder(recipientId, sessionEpoch, conversationId);
        int notificationId = MessageNotifications.stableId("streak:" + conversationId);
        Intent intent = new Intent(context, NotificationOpenActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        request.putInIntent(intent);
        PendingIntent contentIntent = PendingIntent.getActivity(context, notificationId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_streak_flame)
                .setContentTitle(context.getString(R.string.streak_notification_title))
                .setContentText(body(peerName, count))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setContentIntent(contentIntent);
        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build());
            return true;
        } catch (SecurityException permissionRevoked) {
            return false;
        }
    }

    private String body(@Nullable String peerName, int count) {
        if (count >= 2 && peerName != null && !peerName.isBlank()) {
            return context.getString(R.string.streak_notification_text, count, peerName);
        }
        return context.getString(R.string.streak_notification_text_generic);
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reminder_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.reminder_notification_channel_description));
        manager.createNotificationChannel(channel);
    }
}
