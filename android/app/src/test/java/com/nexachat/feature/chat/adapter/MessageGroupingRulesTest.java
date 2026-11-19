package com.nexachat.feature.chat.adapter;

import static org.junit.Assert.assertEquals;

import com.nexachat.core.ui.components.MessageGroupPosition;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.DeliveryStatus;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import org.junit.Test;
import org.junit.After;
import org.junit.Before;

public final class MessageGroupingRulesTest {

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
    public void systemEventsSplitOtherwiseConsecutiveSenderRuns() {
        List<ChatMessage> messages = Arrays.asList(
                message("before-1", "Hello", 1_000L),
                message("before-2", "Again", 2_000L),
                message("system", "CALL|ENDED|120", 3_000L),
                message("after-1", "Back", 4_000L),
                message("after-2", "Thanks", 5_000L)
        );

        assertEquals(MessageGroupPosition.FIRST, MessageGroupingRules.resolveGroupPosition(messages, 0));
        assertEquals(MessageGroupPosition.LAST, MessageGroupingRules.resolveGroupPosition(messages, 1));
        assertEquals(MessageGroupPosition.SINGLE, MessageGroupingRules.resolveGroupPosition(messages, 2));
        assertEquals(MessageGroupPosition.FIRST, MessageGroupingRules.resolveGroupPosition(messages, 3));
        assertEquals(MessageGroupPosition.LAST, MessageGroupingRules.resolveGroupPosition(messages, 4));
    }

    @Test
    public void invalidIndicesAndEmptyListsResolveToSingle() {
        assertEquals(MessageGroupPosition.SINGLE,
                MessageGroupingRules.resolveGroupPosition(Collections.emptyList(), 0));
    }

    @Test
    public void dateSeparatorSplitsOtherwiseAdjacentSameSenderMessages() {
        List<ChatMessage> messages = Arrays.asList(
                message("before-midnight", "Earlier", Instant.parse("2025-05-01T23:59:30Z").toEpochMilli()),
                message("after-midnight", "Later", Instant.parse("2025-05-02T00:00:00Z").toEpochMilli())
        );

        assertEquals(MessageGroupPosition.SINGLE, MessageGroupingRules.resolveGroupPosition(messages, 0));
        assertEquals(MessageGroupPosition.SINGLE, MessageGroupingRules.resolveGroupPosition(messages, 1));
    }

    @Test
    public void precomputedDateBoundariesMatchThePerIndexRuleEverywhere() {
        long day = 86_400_000L;
        long firstDay = Instant.parse("2025-05-01T12:00:00Z").toEpochMilli();
        List<ChatMessage> messages = Arrays.asList(
                message("day1-1", "First", firstDay),
                message("day1-2", "Second", firstDay + 60_000L),
                message("system", "CALL|ENDED|120", firstDay + 120_000L),
                message("day1-3", "Third", firstDay + 180_000L),
                message("day2-1", "Next day", firstDay + day),
                message("day2-2", "Still next day", firstDay + day + 60_000L),
                message("day3-1", "Third day", firstDay + 2 * day)
        );

        boolean[] boundaries = MessageGroupingRules.dateSeparatorBoundaries(messages);
        assertEquals(7, boundaries.length);
        assertEquals("the first row always renders after a separator", true, boundaries[0]);
        assertEquals("same-day neighbours never separate",
                false, boundaries[1]);
        assertEquals("system rows do not force a date separator",
                false, boundaries[2]);
        assertEquals(false, boundaries[3]);
        assertEquals("a day change must be probed exactly once per pair",
                true, boundaries[4]);
        assertEquals(false, boundaries[5]);
        assertEquals(true, boundaries[6]);

        for (int index = 0; index < messages.size(); index++) {
            assertEquals("grouping must be identical to the per-index rule at " + index,
                    MessageGroupingRules.resolveGroupPosition(messages, index),
                    MessageGroupingRules.resolveGroupPosition(messages, index, boundaries));
        }
    }

    @Test
    public void precomputedDateBoundariesHandleTheEmptyList() {
        assertEquals(0, MessageGroupingRules.dateSeparatorBoundaries(Collections.emptyList()).length);
    }

    private static ChatMessage message(String id, String content, long createdAt) {
        return new ChatMessage(
                id,
                "conversation",
                null,
                "sender",
                "Sender",
                "TEXT",
                content,
                null,
                null,
                null,
                createdAt,
                null,
                DeliveryStatus.SENT,
                false,
                Collections.emptyList(),
                Collections.emptyList(),
                null,
                null
        );
    }
}
