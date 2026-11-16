package com.nexachat.feature.chat;

import androidx.annotation.StringRes;
import com.nexachat.R;
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
        return status == DeliveryStatus.SENT || status == DeliveryStatus.DELIVERED
                || status == DeliveryStatus.READ;
    }

    public static boolean isRetryAffordanceVisible(DeliveryStatus status) {
        return status == DeliveryStatus.FAILED;
    }

    /**
     * While the keyset pause holds, a FAILED row can only ever fail again:
     * the durable verdict refuses the resend until re-verification, so the
     * tap-to-retry affordance stays off rather than looping the same refusal.
     */
    public static boolean isRetryAffordanceVisible(
            DeliveryStatus status, boolean sendingPaused) {
        return status == DeliveryStatus.FAILED && !sendingPaused;
    }

    /**
     * The FAILED meta label must agree with the tap: while sends are paused
     * the retry affordance is off for every failed row, so an ordinary
     * failure wears the neutral not-sent stamp instead of promising a retry
     * that does nothing. Only the rows the pause itself failed keep the
     * paused reason.
     */
    @StringRes
    public static int failedRowLabelRes(
            DeliveryStatus status,
            boolean pausedRefusal,
            @StringRes int pausedReasonRes) {
        if (status != DeliveryStatus.FAILED) {
            return 0;
        }
        if (pausedReasonRes != 0) {
            return pausedRefusal ? pausedReasonRes : R.string.message_not_sent;
        }
        return R.string.action_retry;
    }

    public static int resolveSenderPaletteIndex(String senderId) {
        if (senderId == null) {
            return 0;
        }
        // Spread the hash before taking the palette slot: modulo 8 reads only
        // the low 3 bits, and UUID-shaped ids collide systematically there.
        int hash = senderId.hashCode();
        hash ^= hash >>> 16;
        return Math.floorMod(hash, SENDER_PALETTE_SIZE);
    }

    public static int bubbleRowPaddingTopDp(MessageGroupPosition position) {
        return position == MessageGroupPosition.MIDDLE || position == MessageGroupPosition.LAST ? 2 : 4;
    }

    public static int bubbleRowPaddingBottomDp(MessageGroupPosition position) {
        return position == MessageGroupPosition.FIRST || position == MessageGroupPosition.MIDDLE ? 2 : 4;
    }

    /**
     * Corners of a bubble that render at the small "merged" radius instead of
     * the main radius, in {@link android.graphics.drawable.GradientDrawable#setCornerRadii}
     * pair order: [topLeft, topRight, bottomRight, bottomLeft]. The sender-side
     * bottom corner always merges — on the run's last bubble that is the tail —
     * and the sender-side top corner merges on every bubble but the run's
     * first, so consecutive same-sender bubbles stack into one group.
     */
    public static boolean[] bubbleMergedCorners(MessageGroupPosition position, boolean tailOnRight) {
        boolean[] merged = new boolean[4];
        merged[tailOnRight ? 2 : 3] = true;
        merged[tailOnRight ? 1 : 0] =
                position == MessageGroupPosition.MIDDLE || position == MessageGroupPosition.LAST;
        return merged;
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
