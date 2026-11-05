package com.nexachat.core.ui;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class PresenceFormatterTest {

    private static final String ACTIVE_NOW = "Active now";
    private static final String MINS_PATTERN = "Active %1$d min ago";
    private static final String HOURS_PATTERN = "Active %1$d hr ago";
    private static final String YESTERDAY = "Active yesterday";
    private static final String DATE_PATTERN = "dd/MM/yyyy";

    @Test
    public void whenOnlineReturnsActiveNow() {
        String result = PresenceFormatter.formatPresence(
                true,
                1000L,
                5000L,
                ACTIVE_NOW,
                MINS_PATTERN,
                HOURS_PATTERN,
                YESTERDAY,
                DATE_PATTERN,
                Locale.US
        );
        assertEquals(ACTIVE_NOW, result);
    }

    @Test
    public void whenLastSeenNullReturnsNull() {
        String result = PresenceFormatter.formatPresence(
                false,
                null,
                5000L,
                ACTIVE_NOW,
                MINS_PATTERN,
                HOURS_PATTERN,
                YESTERDAY,
                DATE_PATTERN,
                Locale.US
        );
        assertNull(result);
    }

    @Test
    public void whenLastSeenLessThanOneMinuteReturnsActiveNow() {
        long now = 100_000L;
        long lastSeen = now - 30_000L; // 30s ago
        String result = PresenceFormatter.formatPresence(
                false,
                lastSeen,
                now,
                ACTIVE_NOW,
                MINS_PATTERN,
                HOURS_PATTERN,
                YESTERDAY,
                DATE_PATTERN,
                Locale.US
        );
        assertEquals(ACTIVE_NOW, result);
    }

    @Test
    public void whenMinutesAgoFormatsCorrectly() {
        long now = 1_000_000L;
        long lastSeen = now - (15 * 60 * 1000L); // 15 mins ago
        String result = PresenceFormatter.formatPresence(
                false,
                lastSeen,
                now,
                ACTIVE_NOW,
                MINS_PATTERN,
                HOURS_PATTERN,
                YESTERDAY,
                DATE_PATTERN,
                Locale.US
        );
        assertEquals("Active 15 min ago", result);
    }

    @Test
    public void whenHoursAgoFormatsCorrectly() {
        long now = 1_000_000_000L;
        long lastSeen = now - (3 * 3600 * 1000L); // 3 hours ago
        String result = PresenceFormatter.formatPresence(
                false,
                lastSeen,
                now,
                ACTIVE_NOW,
                MINS_PATTERN,
                HOURS_PATTERN,
                YESTERDAY,
                DATE_PATTERN,
                Locale.US
        );
        assertEquals("Active 3 hr ago", result);
    }

    @Test
    public void whenBetween24And48HoursReturnsYesterday() {
        long now = 1_000_000_000L;
        long lastSeen = now - (30 * 3600 * 1000L); // 30 hours ago
        String result = PresenceFormatter.formatPresence(
                false,
                lastSeen,
                now,
                ACTIVE_NOW,
                MINS_PATTERN,
                HOURS_PATTERN,
                YESTERDAY,
                DATE_PATTERN,
                Locale.US
        );
        assertEquals(YESTERDAY, result);
    }

    @Test
    public void whenOlderThan48HoursFormatsDate() {
        long now = 1730000000000L; // Oct 2024
        long lastSeen = now - (72 * 3600 * 1000L); // 72 hours ago
        String result = PresenceFormatter.formatPresence(
                false,
                lastSeen,
                now,
                ACTIVE_NOW,
                MINS_PATTERN,
                HOURS_PATTERN,
                YESTERDAY,
                "dd/MM",
                Locale.US
        );
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM", Locale.US);
        assertEquals(sdf.format(new java.util.Date(lastSeen)), result);
    }
}
