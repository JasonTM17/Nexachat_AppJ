package com.nexachat.feature.chat;

import com.nexachat.core.network.dto.MessageDto;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.DeliveryStatus;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class UnreadDividerRuleTest {

    @Test
    public void sequenceGapCountsAllUnreadAfterAuthoritativeWatermark() {
        List<ChatMessage> messages = Arrays.asList(
                incoming(22L),
                incoming(24L),
                incoming(25L));
        assertEquals(3, UnreadDividerRule.countUnreadIncoming(messages, 20L));
        assertEquals(0, UnreadDividerRule.indexOfFirstUnread(messages, 20L));
    }

    @Test
    public void inferredLastSeqMinusUnreadWouldSkipFirstUnreadWithGaps() {
        List<ChatMessage> messages = Arrays.asList(
                incoming(22L),
                incoming(24L),
                incoming(25L));
        long wrongWatermark = 25L - 3;
        assertEquals(2, UnreadDividerRule.countUnreadIncoming(messages, wrongWatermark));
        assertEquals(1, UnreadDividerRule.indexOfFirstUnread(messages, wrongWatermark));
    }

    @Test
    public void resolveReadWatermarkPrefersInboxLastReadSeq() {
        assertEquals(20L, UnreadDividerRule.resolveReadWatermark(20L, 0L, null, false));
    }

    @Test
    public void zeroUnreadHidesDivider() {
        assertFalse(UnreadDividerRule.shouldShowDivider(0, false, false, 1));
    }

    @Test
    public void oneUnreadShowsWhenNotVisible() {
        assertTrue(UnreadDividerRule.shouldShowDivider(1, false, false, 1));
    }

    @Test
    public void inboxZeroUnreadHidesDividerEvenWhenMessagesDerivedCountPositive() {
        assertFalse(UnreadDividerRule.shouldShowDivider(5, false, false, 0));
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
    public void systemRowsNeverCountAsUnread() {
        List<ChatMessage> messages = Arrays.asList(
                system(21L),
                incoming(22L));
        assertEquals(1, UnreadDividerRule.countUnreadIncoming(messages, 20L));
        assertEquals(1, UnreadDividerRule.indexOfFirstUnread(messages, 20L));
    }

    @Test
    public void allOwnMessagesNeverCount() {
        List<ChatMessage> messages = Arrays.asList(mine(8L), mine(9L), mine(10L));
        assertEquals(0, UnreadDividerRule.countUnreadIncoming(messages, 5L));
        assertEquals(-1, UnreadDividerRule.indexOfFirstUnread(messages, 5L));
    }

    @Test
    public void dismissedOrVisibleHidesDivider() {
        assertFalse(UnreadDividerRule.shouldShowDivider(4, true, false, 4));
        assertFalse(UnreadDividerRule.shouldShowDivider(4, false, true, 4));
    }

    @Test
    public void openAtBottomDoesNotDismissWhenDividerAboveViewport() {
        int dividerPos = 40;
        int firstVisible = 80;
        int lastVisible = 100;
        assertFalse(UnreadDividerRule.shouldDismissDivider(
                dividerPos, firstVisible, lastVisible, false));
    }

    @Test
    public void dismissesAfterDividerWasVisibleAndLeavesViewport() {
        int dividerPos = 50;
        assertFalse(UnreadDividerRule.shouldDismissDivider(dividerPos, 45, 55, true));
        assertTrue(UnreadDividerRule.shouldDismissDivider(dividerPos, 60, 100, true));
    }

    @Test
    public void legacyLastVisibleGreaterThanTargetWouldHaveFalseDismissed() {
        int dividerPos = 40;
        int lastVisible = 100;
        assertTrue(lastVisible >= dividerPos);
        assertFalse(UnreadDividerRule.shouldDismissDivider(dividerPos, 80, lastVisible, false));
    }

    @Test
    public void scrolledPastWatermarkExcludesEarlierMessages() {
        List<ChatMessage> messages = Arrays.asList(incoming(8L), incoming(9L), incoming(10L));
        assertEquals(1, UnreadDividerRule.countUnreadIncoming(messages, 9L));
        assertEquals(2, UnreadDividerRule.indexOfFirstUnread(messages, 9L));
    }

    @Test
    public void pagingCapsWatermarkAtLoadedFloor() {
        List<ChatMessage> messages = Arrays.asList(incoming(22L), incoming(24L));
        assertEquals(20L, UnreadDividerRule.resolveReadWatermark(20L, 0L, messages, true));
        assertEquals(21L, UnreadDividerRule.resolveReadWatermark(0L, 0L, messages, true));
    }

    @Test
    public void readOnOpenRaceRetainsPreOpenMemoryWatermark() {
        long preOpen = UnreadDividerRule.resolveReadWatermark(20L, 18L, null, false);
        long afterMarkRead = UnreadDividerRule.resolveReadWatermark(20L, 25L, null, false);
        assertEquals(20L, preOpen);
        assertEquals(25L, afterMarkRead);
    }

    @Test
    public void newMessageArrivingWhileOpenIncreasesCount() {
        List<ChatMessage> before = Arrays.asList(incoming(11L), incoming(12L));
        List<ChatMessage> after = Arrays.asList(incoming(11L), incoming(12L), incoming(13L));
        assertEquals(2, UnreadDividerRule.countUnreadIncoming(before, 10L));
        assertEquals(3, UnreadDividerRule.countUnreadIncoming(after, 10L));
    }

    private static ChatMessage incoming(long seq) {
        return message(seq, false, MessageDto.TYPE_TEXT);
    }

    private static ChatMessage mine(long seq) {
        return message(seq, true, MessageDto.TYPE_TEXT);
    }

    private static ChatMessage system(long seq) {
        return message(seq, false, "SYSTEM");
    }

    private static ChatMessage message(long seq, boolean mine, String type) {
        return new ChatMessage(
                "id-" + seq,
                "conv",
                seq,
                mine ? "me" : "peer",
                mine ? "Me" : "Peer",
                type,
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
