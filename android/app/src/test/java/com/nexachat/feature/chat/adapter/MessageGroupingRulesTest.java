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
