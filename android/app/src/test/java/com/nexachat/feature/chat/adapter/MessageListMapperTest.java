package com.nexachat.feature.chat.adapter;

import com.nexachat.core.ui.components.DateSeparatorLabels;
import com.nexachat.core.ui.components.MessageGroupPosition;
import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.DeliveryStatus;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class MessageListMapperTest {

    @Test
    public void insertsDateSeparatorOnDayBoundary() {
        long day1 = 1_704_067_200_000L;
        long day2 = day1 + 86_400_000L;
        ChatMessage first = msg("a", day1, false);
        ChatMessage second = msg("b", day2, false);
        DateSeparatorLabels labels = new DateSeparatorLabels("Today", "Yesterday");
        List<MessageListItem> rows = MessageListMapper.buildDisplayItems(
                Arrays.asList(first, second),
                false,
                0L,
                null,
                labels,
                day2
        );
        assertEquals(4, rows.size());
        assertEquals(MessageListItem.Kind.DATE_SEPARATOR, rows.get(0).getKind());
        assertEquals(MessageListItem.Kind.MESSAGE, rows.get(1).getKind());
        assertEquals(MessageListItem.Kind.DATE_SEPARATOR, rows.get(2).getKind());
        assertEquals(MessageListItem.Kind.MESSAGE, rows.get(3).getKind());
    }

    @Test
    public void insertsUnreadDividerBeforeFirstUnreadMessage() {
        ChatMessage read = msg("read", 1L, 1L, false);
        ChatMessage unread = msg("unread", 2L, 2L, false);
        List<MessageListItem> rows = MessageListMapper.buildDisplayItems(
                Arrays.asList(read, unread),
                false,
                0L,
                0L,
                null,
                new DateSeparatorLabels("T", "Y"),
                100_000L,
                1,
                1);
        assertEquals(4, rows.size());
        assertEquals(MessageListItem.Kind.DATE_SEPARATOR, rows.get(0).getKind());
        assertEquals(MessageListItem.Kind.MESSAGE, rows.get(1).getKind());
        assertEquals(MessageListItem.Kind.UNREAD_DIVIDER, rows.get(2).getKind());
        assertEquals(1, rows.get(2).getUnreadDividerCount());
        assertEquals(MessageListItem.Kind.MESSAGE, rows.get(3).getKind());
    }

    @Test
    public void groupChatShowsSenderOnFirstInCluster() {
        ChatMessage first = msg("a", 1L, false);
        ChatMessage second = msg("b", 60_000L, false);
        List<MessageListItem> rows = MessageListMapper.buildDisplayItems(
                Arrays.asList(first, second),
                true,
                0L,
                null,
                new DateSeparatorLabels("T", "Y"),
                100_000L
        );
        List<MessageListItem> messages = rows.stream()
                .filter(i -> i.getKind() == MessageListItem.Kind.MESSAGE)
                .toList();
        assertEquals(2, messages.size());
        assertEquals(MessageGroupPosition.FIRST, messages.get(0).getGroupPosition());
        assertEquals(MessageGroupPosition.LAST, messages.get(1).getGroupPosition());
        assertTrue(messages.get(0).shouldShowSenderName());
        org.junit.Assert.assertFalse(messages.get(1).shouldShowSenderName());
    }

    private static ChatMessage msg(String id, long at, boolean mine) {
        return msg(id, 1L, at, mine);
    }

    private static ChatMessage msg(String id, long seq, long at, boolean mine) {
        return new ChatMessage(
                id,
                "conv",
                seq,
                "peer",
                "Peer",
                MessageDto.TYPE_TEXT,
                "text",
                null,
                null,
                null,
                at,
                null,
                DeliveryStatus.SENT,
                mine,
                Collections.emptyList(),
                Collections.emptyList(),
                null,
                null
        );
    }
}
