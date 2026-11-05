package com.nexachat.feature.chat

import com.nexachat.data.ChatMessage
import com.nexachat.data.DeliveryStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure UI presentation logic unit tests for Chat presentation.
 * Verifies real production functions in [ChatScreen.kt]:
 * - [isMessageReplyable]
 * - [isRetryAffordanceVisible]
 * - [isSeenReceiptVisible]
 * - [resolveBubbleContainerRole]
 */
class ChatUiLogicTest {

    @Test
    fun replyableGateAllowsOnlySentMessages() {
        assertTrue("SENT messages must be replyable", isMessageReplyable(DeliveryStatus.SENT))
        assertFalse("SENDING messages must not be replyable", isMessageReplyable(DeliveryStatus.SENDING))
        assertFalse("FAILED messages must not be replyable", isMessageReplyable(DeliveryStatus.FAILED))
    }

    @Test
    fun retryAffordanceVisibleOnlyForFailedMessages() {
        assertTrue("FAILED messages must expose retry affordance", isRetryAffordanceVisible(DeliveryStatus.FAILED))
        assertFalse("SENDING messages must not show retry affordance", isRetryAffordanceVisible(DeliveryStatus.SENDING))
        assertFalse("SENT messages must not show retry affordance", isRetryAffordanceVisible(DeliveryStatus.SENT))
    }

    @Test
    fun seenReceiptVisibleOnlyInDirectChatForOwnReadMessages() {
        val peerReadSeq = 15L

        // Direct 1-1 chat, own message, sequence number <= peer watermark -> visible
        assertTrue(
            "Own message with seq <= peer watermark in direct chat should show Seen",
            isSeenReceiptVisible(
                isGroup = false,
                mine = true,
                messageSeq = 10L,
                peerReadSeq = peerReadSeq,
            ),
        )

        // Direct 1-1 chat, own message, sequence number exactly equal to peer watermark -> visible
        assertTrue(
            "Own message with seq == peer watermark in direct chat should show Seen",
            isSeenReceiptVisible(
                isGroup = false,
                mine = true,
                messageSeq = 15L,
                peerReadSeq = peerReadSeq,
            ),
        )

        // Direct 1-1 chat, own message, sequence number > peer watermark -> not visible
        assertFalse(
            "Own message with seq > peer watermark should not show Seen",
            isSeenReceiptVisible(
                isGroup = false,
                mine = true,
                messageSeq = 20L,
                peerReadSeq = peerReadSeq,
            ),
        )

        // Direct 1-1 chat, incoming peer message (mine = false) -> never show own seen receipt
        assertFalse(
            "Incoming peer message should never show Seen mark",
            isSeenReceiptVisible(
                isGroup = false,
                mine = false,
                messageSeq = 10L,
                peerReadSeq = peerReadSeq,
            ),
        )

        // Direct 1-1 chat, own message with null seq (pending) -> not visible
        assertFalse(
            "Pending message without sequence number should not show Seen",
            isSeenReceiptVisible(
                isGroup = false,
                mine = true,
                messageSeq = null,
                peerReadSeq = peerReadSeq,
            ),
        )
    }

    @Test
    fun seenReceiptNeverVisibleInGroupChat() {
        // Group chat watermark cannot attribute individual reader -> always false
        assertFalse(
            "Group chat message should never show 1-1 Seen receipt",
            isSeenReceiptVisible(
                isGroup = true,
                mine = true,
                messageSeq = 10L,
                peerReadSeq = 100L,
            ),
        )
    }

    @Test
    fun bubbleContainerRolePrioritizesErrorOverOwnership() {
        // FAILED messages always resolve to ERROR container tint regardless of ownership
        assertEquals(
            BubbleContainerColorRole.ERROR,
            resolveBubbleContainerRole(status = DeliveryStatus.FAILED, mine = true),
        )
        assertEquals(
            BubbleContainerColorRole.ERROR,
            resolveBubbleContainerRole(status = DeliveryStatus.FAILED, mine = false),
        )

        // SENT / SENDING messages use ownership role
        assertEquals(
            BubbleContainerColorRole.MINE,
            resolveBubbleContainerRole(status = DeliveryStatus.SENT, mine = true),
        )
        assertEquals(
            BubbleContainerColorRole.MINE,
            resolveBubbleContainerRole(status = DeliveryStatus.SENDING, mine = true),
        )
        assertEquals(
            BubbleContainerColorRole.PEER,
            resolveBubbleContainerRole(status = DeliveryStatus.SENT, mine = false),
        )
        assertEquals(
            BubbleContainerColorRole.PEER,
            resolveBubbleContainerRole(status = DeliveryStatus.SENDING, mine = false),
        )
    }

    @Test
    fun findMessageReversedIndexResolvesCorrectPositions() {
        val m1 = testMessage("msg-1")
        val m2 = testMessage("msg-2")
        val m3 = testMessage("msg-3")
        val messages = listOf(m1, m2, m3) // chronological: m1 oldest, m3 newest

        // Under reverseLayout:
        // index 0 -> newest message (m3)
        // index 1 -> middle message (m2)
        // index 2 -> oldest message (m1)
        assertEquals("Newest message must map to reverseLayout index 0", 0, findMessageReversedIndex(messages, "msg-3"))
        assertEquals("Middle message must map to reverseLayout index 1", 1, findMessageReversedIndex(messages, "msg-2"))
        assertEquals("Oldest message must map to reverseLayout index 2", 2, findMessageReversedIndex(messages, "msg-1"))
        assertEquals("Non-existent message id must return -1", -1, findMessageReversedIndex(messages, "msg-unknown"))
        assertEquals("Empty message list must return -1", -1, findMessageReversedIndex(emptyList(), "msg-1"))
    }

    private fun testMessage(id: String) = ChatMessage(
        id = id,
        conversationId = "conv-1",
        seq = 1L,
        senderId = "user-1",
        senderDisplayName = "User",
        type = "TEXT",
        content = "test content $id",
        replyToId = null,
        replyToSenderName = null,
        replyToSnippet = null,
        createdAtEpochMs = 1_000L,
        clientMessageId = null,
        status = DeliveryStatus.SENT,
        mine = true,
    )
}
