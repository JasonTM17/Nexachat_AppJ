package com.nexachat.feature.chat;

import com.nexachat.data.ChatMessage;
import com.nexachat.data.DeliveryStatus;

import java.util.List;

/**
 * Pure presentation rules for chat bubble state and message lookup.
 */
public final class ChatBubbleRules {

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

    public static boolean isSeenReceiptVisible(
            boolean isGroup,
            boolean mine,
            Long messageSeq,
            long peerReadSeq
    ) {
        return !isGroup && mine
                && (messageSeq == null ? Long.MAX_VALUE : messageSeq) <= peerReadSeq;
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
