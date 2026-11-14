package com.nexachat.feature.summary.adapter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class SummaryCitationLabelsTest {

    @Test
    public void fallsBackToMessageIdPrefix() {
        assertEquals("abcd", SummaryCitationLabels.messageIdPrefix("abcdef"));
    }

    @Test
    public void truncatesShortMessageIds() {
        assertEquals("ab", SummaryCitationLabels.messageIdPrefix("ab"));
    }

    @Test
    public void senderTimeShowsTimeOnlyForSameDay() {
        // 2026-11-14 17:53 local time approximated via epoch; use Calendar for determinism.
        java.util.Calendar sent = java.util.Calendar.getInstance();
        sent.set(2026, java.util.Calendar.NOVEMBER, 14, 17, 53, 0);
        java.util.Calendar now = java.util.Calendar.getInstance();
        now.set(2026, java.util.Calendar.NOVEMBER, 14, 20, 0, 0);
        String label = SummaryCitationLabels.senderTime(
                "Binh", sent.getTimeInMillis(), now.getTimeInMillis(), java.util.Locale.US);
        assertTrue(label, label.startsWith("Binh · 5:53"));
        assertTrue(label, label.endsWith("PM"));
        assertFalse(label, label.contains("11/14"));
    }

    @Test
    public void senderTimePrefixesDateForEarlierDays() {
        java.util.Calendar sent = java.util.Calendar.getInstance();
        sent.set(2026, java.util.Calendar.NOVEMBER, 12, 9, 5, 0);
        java.util.Calendar now = java.util.Calendar.getInstance();
        now.set(2026, java.util.Calendar.NOVEMBER, 14, 20, 0, 0);
        String label = SummaryCitationLabels.senderTime(
                "Binh", sent.getTimeInMillis(), now.getTimeInMillis(), java.util.Locale.US);
        assertTrue(label, label.startsWith("Binh · "));
        assertTrue(label, label.contains("11/12"));
        assertTrue(label, label.contains("9:05"));
    }

    @Test
    public void senderTimeReturnsNullWithoutSender() {
        assertEquals(null, SummaryCitationLabels.senderTime(
                null, 1000L, 2000L, java.util.Locale.US));
        assertEquals(null, SummaryCitationLabels.senderTime(
                "  ", 1000L, 2000L, java.util.Locale.US));
        assertEquals(null, SummaryCitationLabels.senderTime(
                "Binh", 0L, 2000L, java.util.Locale.US));
    }
}
