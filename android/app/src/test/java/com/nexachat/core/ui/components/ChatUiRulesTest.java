package com.nexachat.core.ui.components;

import com.nexachat.data.ChatMessage;
import com.nexachat.data.DeliveryStatus;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ChatUiRulesTest {
    private TimeZone previousTimeZone;

    @Before
    public void useUtcForCalendarBoundaries() {
        previousTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @After
    public void restoreTimeZone() {
        TimeZone.setDefault(previousTimeZone);
    }

    @Test
    public void groupsConsecutiveMessagesIncludingExactFiveMinuteBoundary() {
        List<ChatMessage> messages = Arrays.asList(
                message("u1", true, 0L),
                message("u1", true, 5 * 60_000L),
                message("u1", true, 10 * 60_000L)
        );

        assertEquals(MessageGroupPosition.FIRST, ChatUiRules.resolveGroupPosition(messages, 0, 5 * 60_000L));
        assertEquals(MessageGroupPosition.MIDDLE, ChatUiRules.resolveGroupPosition(messages, 1, 5 * 60_000L));
        assertEquals(MessageGroupPosition.LAST, ChatUiRules.resolveGroupPosition(messages, 2, 5 * 60_000L));
    }

    @Test
    public void senderOwnershipAndTimeGapsBreakMessageGroups() {
        assertEquals(MessageGroupPosition.SINGLE, ChatUiRules.resolveGroupPosition(
                Arrays.asList(message("u1", true, 0L), message("u2", false, 1L)), 0, 5 * 60_000L));
        assertEquals(MessageGroupPosition.SINGLE, ChatUiRules.resolveGroupPosition(
                Arrays.asList(message("u1", true, 0L), message("u1", false, 1L)), 0, 5 * 60_000L));
        assertEquals(MessageGroupPosition.SINGLE, ChatUiRules.resolveGroupPosition(
                Arrays.asList(message("u1", true, 0L), message("u1", true, 5 * 60_000L + 1)), 0, 5 * 60_000L));
        assertEquals(MessageGroupPosition.SINGLE, ChatUiRules.resolveGroupPosition(Collections.emptyList(), 0, 5 * 60_000L));
        assertEquals(MessageGroupPosition.SINGLE, ChatUiRules.resolveGroupPosition(
                Collections.singletonList(message("u1", true, 0L)), 1, 5 * 60_000L));
    }

    @Test
    public void dateSeparatorHandlesFirstMessageAndMidnightBoundary() {
        long beforeMidnight = epoch("2024-06-01T23:59:59Z");
        long afterMidnight = epoch("2024-06-02T00:00:00Z");

        assertTrue(ChatUiRules.shouldShowDateSeparator(null, beforeMidnight));
        assertFalse(ChatUiRules.shouldShowDateSeparator(beforeMidnight, beforeMidnight + 1));
        assertTrue(ChatUiRules.shouldShowDateSeparator(beforeMidnight, afterMidnight));
        assertTrue(ChatUiRules.shouldShowDateSeparator(epoch("2023-12-31T23:59:59Z"), epoch("2024-01-01T00:00:00Z")));
    }

    @Test
    public void dateLabelsCoverTodayYesterdayLeapDayAndOlderYear() {
        long now = epoch("2024-03-01T12:00:00Z");
        DateSeparatorLabels labels = new DateSeparatorLabels("Hôm nay", "Hôm qua");

        assertEquals("Hôm nay", ChatUiRules.resolveDateSeparatorLabel(epoch("2024-03-01T00:00:00Z"), now, labels, Locale.US));
        assertEquals("Hôm qua", ChatUiRules.resolveDateSeparatorLabel(epoch("2024-02-29T23:00:00Z"), now, labels, Locale.US));
        assertEquals("28/02", ChatUiRules.resolveDateSeparatorLabel(epoch("2024-02-28T08:00:00Z"), now, labels, Locale.US));
        assertEquals("31/12/2023", ChatUiRules.resolveDateSeparatorLabel(epoch("2023-12-31T08:00:00Z"), now, labels, Locale.US));
    }

    @Test
    public void relativeTimeHandlesThresholdFutureAndCalendarLabels() {
        long now = epoch("2024-03-01T12:00:00Z");

        assertEquals("Just now", ChatUiRules.resolveRelativeTimeLabel(now - 59_999L, now, "Just now", "Yesterday", Locale.US));
        assertEquals("11:59", ChatUiRules.resolveRelativeTimeLabel(now - 60_000L, now, "Just now", "Yesterday", Locale.US));
        assertEquals("Just now", ChatUiRules.resolveRelativeTimeLabel(now + 10_000L, now, "Just now", "Yesterday", Locale.US));
        assertEquals("Yesterday", ChatUiRules.resolveRelativeTimeLabel(epoch("2024-02-29T23:00:00Z"), now, "Just now", "Yesterday", Locale.US));
        assertEquals("31/12/23", ChatUiRules.resolveRelativeTimeLabel(epoch("2023-12-31T08:00:00Z"), now, "Just now", "Yesterday", Locale.US));
    }

    private static ChatMessage message(String senderId, boolean mine, long time) {
        return new ChatMessage(
                "m-" + senderId + "-" + time,
                "conversation-1",
                1L,
                senderId,
                "Tên Việt",
                "TEXT",
                "Xin chào",
                null,
                null,
                null,
                time,
                null,
                DeliveryStatus.SENT,
                mine,
                Collections.emptyList(),
                Collections.emptyList(),
                null,
                null
        );
    }

    private static long epoch(String value) {
        return Instant.parse(value).toEpochMilli();
    }
}
