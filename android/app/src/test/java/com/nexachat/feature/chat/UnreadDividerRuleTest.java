package com.nexachat.feature.chat;

import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.ConversationSummary;
import com.nexachat.data.DeliveryStatus;
import com.nexachat.data.PeerPresence;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class UnreadDividerRuleTest {

    @Test
    public void localLastReadSeqFromInboxRow() {
        ConversationSummary row = conversation(100L, 3);
        assertEquals(97L, UnreadDividerRule.localLastReadSeq(row));
    }

    @Test
    public void zeroUnreadHidesDivider() {
        assertFalse(UnreadDividerRule.shouldShowDivider(0, false, false));
    }

    @Test
    public void oneUnreadShowsWhenNotVisible() {
        assertTrue(UnreadDividerRule.shouldShowDivider(1, false, false));
    }

    @Test
    public void manyUnreadCountsIncomingOnly() {
        List<ChatMessage> messages = Arrays.asList(
                incoming(10L),
                incoming(11L),
                mine(12L),
                incoming(13L));
        assertEquals(2, UnreadDividerRule.countUnreadIncoming(messages, 10L));
        assertEquals(1, UnreadDividerRule.indexOfFirstUnread(messages, 10L));
    }

    @Test
    public void allOwnMessagesNeverCount() {
        List<ChatMessage> messages = Arrays.asList(mine(8L), mine(9L), mine(10L));
        assertEquals(0, UnreadDividerRule.countUnreadIncoming(messages, 5L));
        assertEquals(-1, UnreadDividerRule.indexOfFirstUnread(messages, 5L));
    }

    @Test
    public void dismissedOrVisibleHidesDivider() {
        assertFalse(UnreadDividerRule.shouldShowDivider(4, true, false));
        assertFalse(UnreadDividerRule.shouldShowDivider(4, false, true));
    }

    @Test
    public void scrolledPastWatermarkExcludesEarlierMessages() {
        List<ChatMessage> messages = Arrays.asList(incoming(8L), incoming(9L), incoming(10L));
        assertEquals(1, UnreadDividerRule.countUnreadIncoming(messages, 9L));
        assertEquals(2, UnreadDividerRule.indexOfFirstUnread(messages, 9L));
    }

    @Test
    public void newMessageArrivingWhileOpenIncreasesCount() {
        List<ChatMessage> before = Arrays.asList(incoming(11L), incoming(12L));
        List<ChatMessage> after = Arrays.asList(incoming(11L), incoming(12L), incoming(13L));
        assertEquals(2, UnreadDividerRule.countUnreadIncoming(before, 10L));
        assertEquals(3, UnreadDividerRule.countUnreadIncoming(after, 10L));
    }

    private static ConversationSummary conversation(long lastSeq, int unread) {
        return new ConversationSummary(
                "conv",
                "DIRECT",
                null,
                "hi",
                "Peer",
                1L,
                lastSeq,
                unread,
                "Peer",
                null,
                null,
                0L,
                0L,
                null,
                "peer",
                false,
                (PeerPresence) null,
                false,
                null,
                false,
                false,
                false,
                null,
                Collections.emptyList());
    }

    private static ChatMessage incoming(long seq) {
        return message(seq, false);
    }

    private static ChatMessage mine(long seq) {
        return message(seq, true);
    }

    private static ChatMessage message(long seq, boolean mine) {
        return new ChatMessage(
                "id-" + seq,
                "conv",
                seq,
                mine ? "me" : "peer",
                mine ? "Me" : "Peer",
                MessageDto.TYPE_TEXT,
                "text",
                null,
                null,
                null,
                seq * 1000L,
                null,
                DeliveryStatus.SENT,
                mine,
                Collections.emptyList(),
                Collections.emptyList(),
                null,
                null);
    }
}
