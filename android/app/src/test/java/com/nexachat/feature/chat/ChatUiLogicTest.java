package com.nexachat.feature.chat;

import com.nexachat.data.ChatMessage;
import com.nexachat.data.DeliveryStatus;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure UI presentation logic unit tests for Chat presentation.
 * Verifies real production functions in {@link ChatScreenKt}:
 * - {@link ChatScreenKt#isMessageReplyable(DeliveryStatus)}
 * - {@link ChatScreenKt#isRetryAffordanceVisible(DeliveryStatus)}
 * - {@link ChatScreenKt#isSeenReceiptVisible(boolean, boolean, Long, long)}
 * - {@link ChatScreenKt#resolveBubbleContainerRole(DeliveryStatus, boolean)}
 */
public class ChatUiLogicTest {

    @Test
    public void replyableGateAllowsOnlySentMessages() {
        assertTrue("SENT messages must be replyable", ChatScreenKt.isMessageReplyable(DeliveryStatus.SENT));
        assertFalse("SENDING messages must not be replyable", ChatScreenKt.isMessageReplyable(DeliveryStatus.SENDING));
        assertFalse("FAILED messages must not be replyable", ChatScreenKt.isMessageReplyable(DeliveryStatus.FAILED));
    }

    @Test
    public void retryAffordanceVisibleOnlyForFailedMessages() {
        assertTrue("FAILED messages must expose retry affordance",
                ChatScreenKt.isRetryAffordanceVisible(DeliveryStatus.FAILED));
        assertFalse("SENDING messages must not show retry affordance",
                ChatScreenKt.isRetryAffordanceVisible(DeliveryStatus.SENDING));
        assertFalse("SENT messages must not show retry affordance",
                ChatScreenKt.isRetryAffordanceVisible(DeliveryStatus.SENT));
    }

    @Test
    public void seenReceiptVisibleOnlyInDirectChatForOwnReadMessages() {
        long peerReadSeq = 15L;

        // Direct 1-1 chat, own message, sequence number <= peer watermark -> visible
        assertTrue(
                "Own message with seq <= peer watermark in direct chat should show Seen",
                ChatScreenKt.isSeenReceiptVisible(false, true, 10L, peerReadSeq));

        // Direct 1-1 chat, own message, sequence number exactly equal to peer watermark -> visible
        assertTrue(
                "Own message with seq == peer watermark in direct chat should show Seen",
                ChatScreenKt.isSeenReceiptVisible(false, true, 15L, peerReadSeq));

        // Direct 1-1 chat, own message, sequence number > peer watermark -> not visible
        assertFalse(
                "Own message with seq > peer watermark should not show Seen",
                ChatScreenKt.isSeenReceiptVisible(false, true, 20L, peerReadSeq));

        // Direct 1-1 chat, incoming peer message (mine = false) -> never show own seen receipt
        assertFalse(
                "Incoming peer message should never show Seen mark",
                ChatScreenKt.isSeenReceiptVisible(false, false, 10L, peerReadSeq));

        // Direct 1-1 chat, own message with null seq (pending) -> not visible
        assertFalse(
                "Pending message without sequence number should not show Seen",
                ChatScreenKt.isSeenReceiptVisible(false, true, null, peerReadSeq));
    }

    @Test
    public void seenReceiptNeverVisibleInGroupChat() {
        // Group chat watermark cannot attribute individual reader -> always false
        assertFalse(
                "Group chat message should never show 1-1 Seen receipt",
                ChatScreenKt.isSeenReceiptVisible(true, true, 10L, 100L));
    }

    @Test
    public void bubbleContainerRolePrioritizesErrorOverOwnership() {
        // FAILED messages always resolve to ERROR container tint regardless of ownership
        assertEquals(
                BubbleContainerColorRole.ERROR,
                ChatScreenKt.resolveBubbleContainerRole(DeliveryStatus.FAILED, true));
        assertEquals(
                BubbleContainerColorRole.ERROR,
                ChatScreenKt.resolveBubbleContainerRole(DeliveryStatus.FAILED, false));

        // SENT / SENDING messages use ownership role
        assertEquals(
                BubbleContainerColorRole.MINE,
                ChatScreenKt.resolveBubbleContainerRole(DeliveryStatus.SENT, true));
        assertEquals(
                BubbleContainerColorRole.MINE,
                ChatScreenKt.resolveBubbleContainerRole(DeliveryStatus.SENDING, true));
        assertEquals(
                BubbleContainerColorRole.PEER,
                ChatScreenKt.resolveBubbleContainerRole(DeliveryStatus.SENT, false));
        assertEquals(
                BubbleContainerColorRole.PEER,
                ChatScreenKt.resolveBubbleContainerRole(DeliveryStatus.SENDING, false));
    }

    @Test
    public void findMessageReversedIndexResolvesCorrectPositions() {
        ChatMessage m1 = testMessage("msg-1");
        ChatMessage m2 = testMessage("msg-2");
        ChatMessage m3 = testMessage("msg-3");
        List<ChatMessage> messages = Arrays.asList(m1, m2, m3); // chronological: m1 oldest, m3 newest

        // Under reverseLayout:
        // index 0 -> newest message (m3)
        // index 1 -> middle message (m2)
        // index 2 -> oldest message (m1)
        assertEquals("Newest message must map to reverseLayout index 0",
                0, ChatScreenKt.findMessageReversedIndex(messages, "msg-3"));
        assertEquals("Middle message must map to reverseLayout index 1",
                1, ChatScreenKt.findMessageReversedIndex(messages, "msg-2"));
        assertEquals("Oldest message must map to reverseLayout index 2",
                2, ChatScreenKt.findMessageReversedIndex(messages, "msg-1"));
        assertEquals("Non-existent message id must return -1",
                -1, ChatScreenKt.findMessageReversedIndex(messages, "msg-unknown"));
        assertEquals("Empty message list must return -1",
                -1, ChatScreenKt.findMessageReversedIndex(Collections.emptyList(), "msg-1"));
    }

    private static ChatMessage testMessage(String id) {
        return new ChatMessage(
                id,
                "conv-1",
                1L,
                "user-1",
                "User",
                "TEXT",
                "test content " + id,
                null,
                null,
                null,
                1_000L,
                null,
                DeliveryStatus.SENT,
                true,
                Collections.emptyList(),
                Collections.emptyList(),
                null,
                null
        );
    }
}
