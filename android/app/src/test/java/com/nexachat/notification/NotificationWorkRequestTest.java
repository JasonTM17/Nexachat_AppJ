package com.nexachat.notification;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.work.Data;
import org.junit.Test;

public final class NotificationWorkRequestTest {
    @Test
    public void messageKeyIsBoundToAccountEpochAndMessage() {
        NotificationWorkRequest request = NotificationWorkRequest.message(
                "account-a", 4L, "conversation-1", "message-9", 9L);

        assertEquals("msg:account-a:4:message-9", request.uniqueWorkName());
        assertEquals(request.uniqueWorkName(), request.occurrenceKey());
        assertEquals(request.uniqueWorkName(),
                NotificationWorkRequest.fromData(request.toData()).uniqueWorkName());
    }

    @Test
    public void reminderRescheduleCreatesANewOccurrence() {
        NotificationWorkRequest first = NotificationWorkRequest.reminder(
                "account-a", 4L, "conversation-1", "reminder-2", "0123456789abcdef0123456789abcdef");
        NotificationWorkRequest rescheduled = NotificationWorkRequest.reminder(
                "account-a", 4L, "conversation-1", "reminder-2", "fedcba9876543210fedcba9876543210");

        assertEquals("rem:account-a:4:reminder-2:0123456789abcdef0123456789abcdef", first.uniqueWorkName());
        assertNotEquals(first.uniqueWorkName(), rescheduled.uniqueWorkName());
        assertEquals(rescheduled.uniqueWorkName(),
                NotificationWorkRequest.fromData(rescheduled.toData()).occurrenceKey());
    }

    @Test
    public void legacyOrMalformedWorkInputFailsClosed() {
        assertNull(NotificationWorkRequest.fromData(new Data.Builder()
                .putString("type", NotificationWorkRequest.TYPE_MESSAGE)
                .putString("conversationId", "conversation-1")
                .putString("messageId", "message-9")
                .putLong("seq", 9L)
                .build()));
        assertFalse(NotificationWorkRequest.reminder(
                "account-a", 4L, "conversation-1", "reminder-2", "not-an-instant").isValid());
        assertTrue(NotificationWorkRequest.message(
                "account-a", 4L, "conversation-1", "message-9", 9L).isValid());
        assertNotNull(NotificationWorkRequest.reminder(
                "account-a", 4L, "conversation-1", "reminder-2", "0123456789abcdef0123456789abcdef"));
    }
}
