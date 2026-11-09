package com.nexachat.feature.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nexachat.core.ui.components.MessageGroupPosition;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.DeliveryStatus;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class ChatBubbleRulesTest {

    @Test
    public void senderNamePaletteIndexIsStableAndBounded() {
        String senderId = "sender-42";
        int expected = Math.floorMod(senderId.hashCode(), ChatBubbleRules.SENDER_PALETTE_SIZE);

        assertEquals(expected, ChatBubbleRules.resolveSenderPaletteIndex(senderId));
        assertEquals(expected, ChatBubbleRules.resolveSenderPaletteIndex(senderId));
        assertTrue(ChatBubbleRules.resolveSenderPaletteIndex(null) >= 0);
        assertTrue(ChatBubbleRules.resolveSenderPaletteIndex(null) < ChatBubbleRules.SENDER_PALETTE_SIZE);
    }

    @Test
    public void bubbleRowPaddingKeepsRunsTightAndGroupsSeparated() {
        assertEquals(4, ChatBubbleRules.bubbleRowPaddingTopDp(MessageGroupPosition.SINGLE));
        assertEquals(4, ChatBubbleRules.bubbleRowPaddingBottomDp(MessageGroupPosition.SINGLE));
        assertEquals(4, ChatBubbleRules.bubbleRowPaddingTopDp(MessageGroupPosition.FIRST));
        assertEquals(1, ChatBubbleRules.bubbleRowPaddingBottomDp(MessageGroupPosition.FIRST));
        assertEquals(1, ChatBubbleRules.bubbleRowPaddingTopDp(MessageGroupPosition.MIDDLE));
        assertEquals(1, ChatBubbleRules.bubbleRowPaddingBottomDp(MessageGroupPosition.MIDDLE));
        assertEquals(1, ChatBubbleRules.bubbleRowPaddingTopDp(MessageGroupPosition.LAST));
        assertEquals(4, ChatBubbleRules.bubbleRowPaddingBottomDp(MessageGroupPosition.LAST));
    }

    @Test
    public void seenReceiptVisibleForOneOnOneChatsWhenMineAndRead() {
        // 1-1 chat: mine, seq <= peerReadSeq -> true
        assertTrue(ChatBubbleRules.isSeenReceiptVisible(false, true, 10L, 10L));
        assertTrue(ChatBubbleRules.isSeenReceiptVisible(false, true, 5L, 10L));

        // Group chat: watermark cannot attribute individual readers -> always false
        assertFalse(ChatBubbleRules.isSeenReceiptVisible(true, true, 10L, 10L));
        assertFalse(ChatBubbleRules.isSeenReceiptVisible(true, true, 8L, 12L));

        // When message seq exceeds peerReadSeq -> false
        assertFalse(ChatBubbleRules.isSeenReceiptVisible(false, true, 11L, 10L));
        assertFalse(ChatBubbleRules.isSeenReceiptVisible(true, true, 15L, 10L));

        // When not mine -> false
        assertFalse(ChatBubbleRules.isSeenReceiptVisible(false, false, 5L, 10L));
        assertFalse(ChatBubbleRules.isSeenReceiptVisible(true, false, 5L, 10L));

        // Edge case: messageSeq null or <= 0 -> false
        assertFalse(ChatBubbleRules.isSeenReceiptVisible(false, true, null, 10L));
        assertFalse(ChatBubbleRules.isSeenReceiptVisible(false, true, 0L, 10L));
    }

    @Test
    public void seenReceiptVisibleObeysShowSeenPrivacyRuleMatrix() {
        // When showSeen is true: behaves identically to standard seen rules
        assertTrue(ChatBubbleRules.isSeenReceiptVisible(false, true, 10L, 10L, true));
        assertTrue(ChatBubbleRules.isSeenReceiptVisible(false, true, 5L, 10L, true));
        assertFalse(ChatBubbleRules.isSeenReceiptVisible(true, true, 5L, 10L, true)); // group
        assertFalse(ChatBubbleRules.isSeenReceiptVisible(false, false, 5L, 10L, true)); // peer message
        assertFalse(ChatBubbleRules.isSeenReceiptVisible(false, true, 15L, 10L, true)); // unread seq

        // When showSeen is false: reciprocal privacy hides blue ticks entirely
        assertFalse(ChatBubbleRules.isSeenReceiptVisible(false, true, 10L, 10L, false));
        assertFalse(ChatBubbleRules.isSeenReceiptVisible(false, true, 5L, 10L, false));
        assertFalse(ChatBubbleRules.isSeenReceiptVisible(true, true, 5L, 10L, false));
        assertFalse(ChatBubbleRules.isSeenReceiptVisible(false, false, 5L, 10L, false));
        assertFalse(ChatBubbleRules.isSeenReceiptVisible(false, true, 15L, 10L, false));
    }

    @Test
    public void deliveredReceiptVisibleForOneOnOneChatsWhenMineAndDelivered() {
        // 1-1 chat: mine, seq <= peerDeliveredSeq -> true
        assertTrue(ChatBubbleRules.isDeliveredReceiptVisible(false, true, 10L, 10L));
        assertTrue(ChatBubbleRules.isDeliveredReceiptVisible(false, true, 3L, 10L));

        // Group chat: watermark cannot attribute individual delivery -> always false
        assertFalse(ChatBubbleRules.isDeliveredReceiptVisible(true, true, 10L, 10L));
        assertFalse(ChatBubbleRules.isDeliveredReceiptVisible(true, true, 4L, 10L));

        // When message seq exceeds peerDeliveredSeq -> false
        assertFalse(ChatBubbleRules.isDeliveredReceiptVisible(false, true, 11L, 10L));
        assertFalse(ChatBubbleRules.isDeliveredReceiptVisible(true, true, 20L, 10L));

        // When not mine -> false
        assertFalse(ChatBubbleRules.isDeliveredReceiptVisible(false, false, 5L, 10L));
        assertFalse(ChatBubbleRules.isDeliveredReceiptVisible(true, false, 5L, 10L));

        // Edge case: messageSeq null or <= 0 -> false
        assertFalse(ChatBubbleRules.isDeliveredReceiptVisible(false, true, null, 10L));
        assertFalse(ChatBubbleRules.isDeliveredReceiptVisible(false, true, 0L, 10L));
    }

    @Test
    public void resolveBubbleContainerRole() {
        assertEquals(BubbleContainerColorRole.ERROR,
                ChatBubbleRules.resolveBubbleContainerRole(DeliveryStatus.FAILED, true));
        assertEquals(BubbleContainerColorRole.ERROR,
                ChatBubbleRules.resolveBubbleContainerRole(DeliveryStatus.FAILED, false));
        assertEquals(BubbleContainerColorRole.MINE,
                ChatBubbleRules.resolveBubbleContainerRole(DeliveryStatus.SENT, true));
        assertEquals(BubbleContainerColorRole.PEER,
                ChatBubbleRules.resolveBubbleContainerRole(DeliveryStatus.SENT, false));
    }

    @Test
    public void messageReplyableAndRetryAffordance() {
        assertTrue(ChatBubbleRules.isMessageReplyable(DeliveryStatus.SENT));
        assertFalse(ChatBubbleRules.isMessageReplyable(DeliveryStatus.FAILED));
        assertFalse(ChatBubbleRules.isMessageReplyable(DeliveryStatus.SENDING));

        assertTrue(ChatBubbleRules.isRetryAffordanceVisible(DeliveryStatus.FAILED));
        assertFalse(ChatBubbleRules.isRetryAffordanceVisible(DeliveryStatus.SENT));
        assertFalse(ChatBubbleRules.isRetryAffordanceVisible(DeliveryStatus.DELIVERED));
    }

    @Test
    public void findMessageReversedIndexReturnsCorrectOffset() {
        ChatMessage m1 = new ChatMessage("msg-1", "c1", 1L, "u1", "User", "TEXT", "1",
                null, null, null, 100L, null, DeliveryStatus.SENT, true,
                Collections.emptyList(), Collections.emptyList(), null, null);
        ChatMessage m2 = new ChatMessage("msg-2", "c1", 2L, "u1", "User", "TEXT", "2",
                null, null, null, 200L, null, DeliveryStatus.SENT, true,
                Collections.emptyList(), Collections.emptyList(), null, null);

        List<ChatMessage> list = List.of(m1, m2);
        // Reversed index: m2 is index 0, m1 is index 1
        assertEquals(0, ChatBubbleRules.findMessageReversedIndex(list, "msg-2"));
        assertEquals(1, ChatBubbleRules.findMessageReversedIndex(list, "msg-1"));
        assertEquals(-1, ChatBubbleRules.findMessageReversedIndex(list, "msg-unknown"));
    }
}
