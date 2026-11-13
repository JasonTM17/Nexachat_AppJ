package com.nexachat.notification;

import static org.mockito.Mockito.*;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.service.notification.StatusBarNotification;
import com.nexachat.core.network.AuthApi;
import com.nexachat.core.network.SessionEvents;
import com.nexachat.core.network.TokenRefresher;
import com.nexachat.core.storage.TokenStore;
import com.nexachat.data.AuthRepository;
import com.nexachat.call.service.CallNotifications;
import javax.inject.Inject;
import org.junit.Before;
import org.junit.Test;

public final class SessionNotificationCleanupTest {
    private NotificationManager manager;
    private SessionEvents events;
    private int messageId;
    private int reminderId;

    @Before public void setUp() throws Exception {
        Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        manager = mock(NotificationManager.class);
        when(context.getSystemService(NotificationManager.class)).thenReturn(manager);
        messageId = MessageNotifications.notificationId("owner", "conversation");
        reminderId = MessageNotifications.stableId(NotificationWorkRequest.reminder(
                "owner", 1L, "conversation", "reminder", "a".repeat(32)).occurrenceKey());
        StatusBarNotification[] posted = new StatusBarNotification[] {
                alert("messages", null, messageId),
                alert("messages", "legacy-message", 42),
                alert("reminders", null, reminderId),
                alert(CallNotifications.CHANNEL_ID, null, CallNotifications.INCOMING_NOTIFICATION_ID),
                alert(CallNotifications.ONGOING_CHANNEL_ID, null, CallNotifications.ONGOING_NOTIFICATION_ID),
                alert("other", null, 202)
        };
        when(manager.getActiveNotifications()).thenReturn(posted);
        events = new SessionEvents();
        AuthRepository repository = new AuthRepository(mock(AuthApi.class), mock(TokenStore.class),
                events, mock(TokenRefresher.class), mock(DeviceTokenRegistrationManager.class));
        MessageNotifications messages = new MessageNotifications(context);
        ReminderNotifications reminders = new ReminderNotifications(context);
        // Exercise the same method-injection boundary as Hilt, including the baseline
        // where no notification lifecycle observer was registered at all.
        for (var method : AuthRepository.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Inject.class)) {
                method.setAccessible(true);
                method.invoke(repository, messages, reminders);
            }
        }
    }

    @Test public void logoutDismissesMessageAndReminderIdsButPreservesCalls() {
        events.emitLoggedOut();
        assertOnlySessionAlertsCancelled();
    }

    @Test public void sessionClosingDismissesAlertsBeforeRemoteLogoutFinishes() {
        events.emitSessionClosing();
        assertOnlySessionAlertsCancelled();
    }

    @Test public void reauthenticationDismissesAlerts() {
        events.emitReauthenticationRequired();
        assertOnlySessionAlertsCancelled();
    }

    @Test public void sessionAvailableAndRefreshDoNotDismissCurrentAlerts() {
        events.emitSessionAvailable();
        events.emitSessionRefreshed();
        verifyNoInteractions(manager);
    }

    @Test public void unavailableNotificationAccessDoesNotBreakSessionEvents() {
        when(manager.getActiveNotifications()).thenThrow(new SecurityException("unavailable"));
        var observed = events.getEvents().test();
        events.emitLoggedOut();
        observed.assertValue(SessionEvents.Event.LoggedOut.INSTANCE).assertNoErrors();
        verify(manager, never()).cancelAll();
        observed.cancel();
    }

    @Test public void repeatedLogoutWithNoActiveAlertsIsHarmless() {
        when(manager.getActiveNotifications()).thenReturn(new StatusBarNotification[0]);
        events.emitSessionClosing();
        events.emitLoggedOut();
        verify(manager, never()).cancel(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt());
        verify(manager, never()).cancelAll();
    }

    private void assertOnlySessionAlertsCancelled() {
        verify(manager).cancel(null, messageId);
        verify(manager).cancel("legacy-message", 42);
        verify(manager).cancel(null, reminderId);
        verify(manager, never()).cancel(null, CallNotifications.INCOMING_NOTIFICATION_ID);
        verify(manager, never()).cancel(null, CallNotifications.ONGOING_NOTIFICATION_ID);
        verify(manager, never()).cancel(null, 202);
        verify(manager, never()).cancelAll();
    }

    private StatusBarNotification alert(String channel, String tag, int id) {
        Notification notification = mock(Notification.class);
        when(notification.getChannelId()).thenReturn(channel);
        StatusBarNotification posted = mock(StatusBarNotification.class);
        when(posted.getNotification()).thenReturn(notification);
        when(posted.getTag()).thenReturn(tag);
        when(posted.getId()).thenReturn(id);
        return posted;
    }
}
