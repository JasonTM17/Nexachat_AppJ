package com.nexachat.feature.chat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.data.ChatMessage;
import com.nexachat.data.ConversationSummary;
import java.util.List;

/**
 * Pure rules for the in-list "unread messages" divider shown when opening a chat.
 */
public final class UnreadDividerRule {

    private static final String TYPE_SYSTEM = "SYSTEM";

    private UnreadDividerRule() {
    }

    /**
     * Combines inbox, in-memory, and loaded-history anchors into the read watermark
     * used to place the divider. Never infers read position from {@code lastSeq - unreadCount}.
     */
    public static long resolveReadWatermark(
            long inboxLastReadSeq,
            long memoryLastReadSeq,
            @Nullable List<ChatMessage> chronological,
            boolean hasMoreOlder) {
        long authoritative = Math.max(Math.max(0L, inboxLastReadSeq), Math.max(0L, memoryLastReadSeq));
        if (chronological == null || chronological.isEmpty()) {
            return authoritative;
        }
        long loadedAnchor = loadedHistoryReadCeiling(chronological, hasMoreOlder);
        if (authoritative > 0L) {
            return hasMoreOlder ? Math.min(authoritative, loadedAnchor) : authoritative;
        }
        return loadedAnchor;
    }

    /**
     * @deprecated Use {@link #resolveReadWatermark(long, long, List, boolean)} with
     *     {@link ConversationSummary#getMyLastReadSeq()} instead of subtracting unread count.
     */
    @Deprecated
    public static long localLastReadSeq(@NonNull ConversationSummary conversation) {
        return Math.max(0L, conversation.getMyLastReadSeq());
    }

    public static int countUnreadIncoming(
            @NonNull List<ChatMessage> chronological,
            long localLastReadSeq
    ) {
        int count = 0;
        for (ChatMessage message : chronological) {
            if (countsAsUnread(message, localLastReadSeq)) {
                count++;
            }
        }
        return count;
    }

    public static int indexOfFirstUnread(
            @NonNull List<ChatMessage> chronological,
            long localLastReadSeq
    ) {
        for (int index = 0; index < chronological.size(); index++) {
            if (countsAsUnread(chronological.get(index), localLastReadSeq)) {
                return index;
            }
        }
        return -1;
    }

    public static boolean countsAsUnread(@Nullable ChatMessage message, long localLastReadSeq) {
        if (message == null || message.getMine()) {
            return false;
        }
        if (TYPE_SYSTEM.equalsIgnoreCase(message.getType())) {
            return false;
        }
        Long seq = message.getSeq();
        return seq != null && seq > localLastReadSeq;
    }

    public static boolean shouldShowDivider(
            int unreadCount,
            boolean dismissed,
            boolean firstUnreadVisible,
            int inboxUnreadCount
    ) {
        if (dismissed || unreadCount <= 0 || firstUnreadVisible || inboxUnreadCount <= 0) {
            return false;
        }
        return true;
    }

    public static boolean isDividerAdapterPositionVisible(
            int dividerAdapterPosition,
            int firstVisible,
            int lastVisible
    ) {
        if (dividerAdapterPosition < 0 || firstVisible < 0 || lastVisible < 0) {
            return false;
        }
        return dividerAdapterPosition >= firstVisible && dividerAdapterPosition <= lastVisible;
    }

    /**
     * Dismiss only after the divider row has been on screen and then leaves the viewport.
     * Opening scrolled to the bottom must not dismiss when the divider sits above the window.
     */
    public static boolean shouldDismissDivider(
            int dividerAdapterPosition,
            int firstVisible,
            int lastVisible,
            boolean dividerWasVisible
    ) {
        if (dividerAdapterPosition < 0) {
            return false;
        }
        if (isDividerAdapterPositionVisible(dividerAdapterPosition, firstVisible, lastVisible)) {
            return false;
        }
        return dividerWasVisible;
    }

    private static long loadedHistoryReadCeiling(
            @NonNull List<ChatMessage> chronological,
            boolean hasMoreOlder
    ) {
        long minSeq = Long.MAX_VALUE;
        for (ChatMessage message : chronological) {
            Long seq = message.getSeq();
            if (seq != null && seq < minSeq) {
                minSeq = seq;
            }
        }
        if (minSeq == Long.MAX_VALUE) {
            return 0L;
        }
        if (hasMoreOlder) {
            return Math.max(0L, minSeq - 1L);
        }
        return 0L;
    }
}
