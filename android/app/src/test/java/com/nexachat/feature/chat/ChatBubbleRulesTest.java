package com.nexachat.feature.chat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.nexachat.R;
import com.nexachat.core.ui.components.MessageGroupPosition;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.DeliveryStatus;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class ChatBubbleRulesTest {

    @Test
    public void senderNamePaletteIndexIsStableAndBounded() {
        assertEquals(4, ChatBubbleRules.resolveSenderPaletteIndex("sender-42"));
        assertEquals(7, ChatBubbleRules.resolveSenderPaletteIndex("alice-7"));
        assertEquals(4, ChatBubbleRules.resolveSenderPaletteIndex("peer-99"));
        assertEquals(4, ChatBubbleRules.resolveSenderPaletteIndex("sender-42"));

        assertEquals(0, ChatBubbleRules.resolveSenderPaletteIndex(null));
        assertTrue(ChatBubbleRules.resolveSenderPaletteIndex("boundary-x") >= 0);
        assertTrue(ChatBubbleRules.resolveSenderPaletteIndex("boundary-x")
                < ChatBubbleRules.SENDER_PALETTE_SIZE);
    }

    @Test
    public void uuidShapedSenderIdsDoNotCollapseOntoOnePaletteSlot() {
        // Modulo 8 reads only the low 3 bits of String.hashCode(); sequential
        // UUIDs all landed on one slot before the spreader was added.
        java.util.Set<Integer> slots = new java.util.HashSet<>();
        slots.add(ChatBubbleRules.resolveSenderPaletteIndex(
                "11111111-1111-4111-8111-111111111111"));
        slots.add(ChatBubbleRules.resolveSenderPaletteIndex(
                "22222222-2222-4222-8222-222222222222"));
        slots.add(ChatBubbleRules.resolveSenderPaletteIndex(
                "33333333-3333-4333-8333-333333333333"));
        slots.add(ChatBubbleRules.resolveSenderPaletteIndex(
                "44444444-4444-4444-8444-444444444444"));
        assertTrue("sequential UUIDs must color at least three senders distinctly",
                slots.size() >= 3);
    }

    @Test
    public void bubbleRowPaddingKeepsRunsTightAndGroupsSeparated() {
        assertEquals(4, ChatBubbleRules.bubbleRowPaddingTopDp(MessageGroupPosition.SINGLE));
        assertEquals(4, ChatBubbleRules.bubbleRowPaddingBottomDp(MessageGroupPosition.SINGLE));
        assertEquals(4, ChatBubbleRules.bubbleRowPaddingTopDp(MessageGroupPosition.FIRST));
        assertEquals(2, ChatBubbleRules.bubbleRowPaddingBottomDp(MessageGroupPosition.FIRST));
        assertEquals(2, ChatBubbleRules.bubbleRowPaddingTopDp(MessageGroupPosition.MIDDLE));
        assertEquals(2, ChatBubbleRules.bubbleRowPaddingBottomDp(MessageGroupPosition.MIDDLE));
        assertEquals(2, ChatBubbleRules.bubbleRowPaddingTopDp(MessageGroupPosition.LAST));
        assertEquals(4, ChatBubbleRules.bubbleRowPaddingBottomDp(MessageGroupPosition.LAST));
    }

    @Test
    public void mergedCornersStackRunOnTheSenderSide() {
        // Corner pair order: [topLeft, topRight, bottomRight, bottomLeft].
        assertArrayEquals(
                new boolean[]{false, false, true, false},
                ChatBubbleRules.bubbleMergedCorners(MessageGroupPosition.SINGLE, true));
        assertArrayEquals(
                new boolean[]{false, false, false, true},
                ChatBubbleRules.bubbleMergedCorners(MessageGroupPosition.SINGLE, false));

        assertArrayEquals(
                new boolean[]{false, false, true, false},
                ChatBubbleRules.bubbleMergedCorners(MessageGroupPosition.FIRST, true));
        assertArrayEquals(
                new boolean[]{false, true, true, false},
                ChatBubbleRules.bubbleMergedCorners(MessageGroupPosition.MIDDLE, true));
        assertArrayEquals(
                new boolean[]{false, true, true, false},
                ChatBubbleRules.bubbleMergedCorners(MessageGroupPosition.LAST, true));

        assertArrayEquals(
                new boolean[]{false, false, false, true},
                ChatBubbleRules.bubbleMergedCorners(MessageGroupPosition.FIRST, false));
        assertArrayEquals(
                new boolean[]{true, false, false, true},
                ChatBubbleRules.bubbleMergedCorners(MessageGroupPosition.MIDDLE, false));
        assertArrayEquals(
                new boolean[]{true, false, false, true},
                ChatBubbleRules.bubbleMergedCorners(MessageGroupPosition.LAST, false));
    }

    @Test
    public void mergedCornersNeverMergeTheInnerSide() {
        for (MessageGroupPosition position : MessageGroupPosition.values()) {
            boolean[] rightTail = ChatBubbleRules.bubbleMergedCorners(position, true);
            assertFalse(rightTail[0]);
            assertFalse(rightTail[3]);
            boolean[] leftTail = ChatBubbleRules.bubbleMergedCorners(position, false);
            assertFalse(leftTail[1]);
            assertFalse(leftTail[2]);
        }
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
        assertTrue(ChatBubbleRules.isMessageReplyable(DeliveryStatus.DELIVERED));
        assertTrue(ChatBubbleRules.isMessageReplyable(DeliveryStatus.READ));
        assertFalse(ChatBubbleRules.isMessageReplyable(DeliveryStatus.FAILED));
        assertFalse(ChatBubbleRules.isMessageReplyable(DeliveryStatus.SENDING));

        assertTrue(ChatBubbleRules.isRetryAffordanceVisible(DeliveryStatus.FAILED));
        assertFalse(ChatBubbleRules.isRetryAffordanceVisible(DeliveryStatus.SENT));
        assertFalse(ChatBubbleRules.isRetryAffordanceVisible(DeliveryStatus.DELIVERED));
    }

    @Test
    public void pausedConversationDisablesTheRetryAffordance() {
        assertFalse(ChatBubbleRules.isRetryAffordanceVisible(DeliveryStatus.FAILED, true));
        assertTrue(ChatBubbleRules.isRetryAffordanceVisible(DeliveryStatus.FAILED, false));
        assertFalse(ChatBubbleRules.isRetryAffordanceVisible(DeliveryStatus.SENT, false));
    }

    @Test
    public void pausedConversationLabelsOrdinaryFailuresWithoutARetryPromise() {
        // The pause suppressed the retry tap for every FAILED row, so the
        // label on a row the pause did not stamp must not say "Retry".
        assertEquals(R.string.message_not_sent,
                ChatBubbleRules.failedRowLabelRes(
                        DeliveryStatus.FAILED, false, R.string.e2ee_failed_keyset_changed));
        // Rows the pause itself failed keep their honest reason.
        assertEquals(R.string.e2ee_failed_keyset_changed,
                ChatBubbleRules.failedRowLabelRes(
                        DeliveryStatus.FAILED, true, R.string.e2ee_failed_keyset_changed));
        // With no pause active, the affordance is live and the label may retry.
        assertEquals(R.string.action_retry,
                ChatBubbleRules.failedRowLabelRes(DeliveryStatus.FAILED, true, 0));
        assertEquals(R.string.action_retry,
                ChatBubbleRules.failedRowLabelRes(DeliveryStatus.FAILED, false, 0));
        assertEquals(0,
                ChatBubbleRules.failedRowLabelRes(DeliveryStatus.SENT, false, 0));
    }

    @Test
    public void actionMessageResolvesFromRetainedStateWhileRenderedListIsEmpty() {
        // Recreation restores the child sheet before the host view lifecycle
        // republishes its mirror: the rendered list is still the initial
        // empty state, and the retained ViewModel list is the only source
        // that already holds the message.
        ChatMessage m1 = testMessage("msg-1");
        ChatMessage m2 = testMessage("msg-2");

        assertSame(m2, ChatBubbleRules.findActionMessage(
                List.of(m1, m2), Collections.emptyList(), "msg-2"));
    }

    @Test
    public void actionMessageFallsBackToTheRenderedListWithoutARetainedState() {
        ChatMessage m1 = testMessage("msg-1");

        assertSame(m1, ChatBubbleRules.findActionMessage(
                null, List.of(m1), "msg-1"));
        assertNull(ChatBubbleRules.findActionMessage(
                null, List.of(m1), "msg-unknown"));
    }

    @Test
    public void actionMessagePrefersTheRetainedListOverTheRenderedMirror() {
        // The retained state is at least as fresh as the rendered mirror, so
        // a message absent from it is treated as gone rather than looked up
        // in the lagging mirror.
        ChatMessage stale = testMessage("msg-1");
        ChatMessage fresh = testMessage("msg-1");

        assertSame(fresh, ChatBubbleRules.findActionMessage(
                List.of(fresh), List.of(stale), "msg-1"));
        assertNull(ChatBubbleRules.findActionMessage(
                Collections.emptyList(), List.of(stale), "msg-1"));
        assertNull(ChatBubbleRules.findActionMessage(
                List.of(fresh), List.of(stale), "msg-unknown"));
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

    private static ChatMessage testMessage(String id) {
        return new ChatMessage(id, "c1", 1L, "u1", "User", "TEXT", "body " + id,
                null, null, null, 100L, null, DeliveryStatus.SENT, true,
                Collections.emptyList(), Collections.emptyList(), null, null);
    }
}
