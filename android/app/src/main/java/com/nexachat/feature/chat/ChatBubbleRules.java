package com.nexachat.feature.chat;

import com.nexachat.data.ChatMessage;
import com.nexachat.data.DeliveryStatus;
import com.nexachat.core.ui.components.MessageGroupPosition;

import java.util.List;

/**
 * Pure presentation rules for chat bubble state and message lookup.
 */
public final class ChatBubbleRules {

    public static final int SENDER_PALETTE_SIZE = 8;

    private ChatBubbleRules() {}

    public static BubbleContainerColorRole resolveBubbleContainerRole(DeliveryStatus status, boolean mine) {
        if (status == DeliveryStatus.FAILED) {
            return BubbleContainerColorRole.ERROR;
        }
        return mine ? BubbleContainerColorRole.MINE : BubbleContainerColorRole.PEER;
    }

    public static boolean isMessageReplyable(DeliveryStatus status) {
        return status == DeliveryStatus.SENT;
    }

    public static boolean isRetryAffordanceVisible(DeliveryStatus status) {
        return status == DeliveryStatus.FAILED;
    }

    public static int resolveSenderPaletteIndex(String senderId) {
        return Math.floorMod(senderId == null ? 0 : senderId.hashCode(), SENDER_PALETTE_SIZE);
    }

    public static int bubbleRowPaddingTopDp(MessageGroupPosition position) {
        return position == MessageGroupPosition.MIDDLE || position == MessageGroupPosition.LAST ? 1 : 4;
    }

    public static int bubbleRowPaddingBottomDp(MessageGroupPosition position) {
        return position == MessageGroupPosition.FIRST || position == MessageGroupPosition.MIDDLE ? 1 : 4;
    }

    public static boolean isSeenReceiptVisible(
            boolean isGroup,
            boolean mine,
            Long messageSeq,
            long peerReadSeq,
            boolean showSeen
    ) {
        return showSeen && !isGroup && mine && messageSeq != null && messageSeq > 0 && messageSeq <= peerReadSeq;
    }

    public static boolean isSeenReceiptVisible(
            boolean isGroup,
            boolean mine,
            Long messageSeq,
            long peerReadSeq
    ) {
        return isSeenReceiptVisible(isGroup, mine, messageSeq, peerReadSeq, true);
    }

    public static boolean isDeliveredReceiptVisible(
            boolean isGroup,
            boolean mine,
            Long messageSeq,
            long peerDeliveredSeq
    ) {
        return !isGroup && mine && messageSeq != null && messageSeq > 0 && messageSeq <= peerDeliveredSeq;
    }

    public static int findMessageReversedIndex(List<ChatMessage> messages, String targetId) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getId().equals(targetId)) {
                return messages.size() - 1 - i;
            }
        }
        return -1;
    }
}
