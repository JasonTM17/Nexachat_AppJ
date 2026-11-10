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

    private UnreadDividerRule() {
    }

    /**
     * Local last-read sequence implied by the cached inbox row before the chat screen marks read.
     */
    public static long localLastReadSeq(@NonNull ConversationSummary conversation) {
        long lastSeq = Math.max(0L, conversation.getLastMessageSeq());
        int unread = Math.max(0, conversation.getUnreadCount());
        return Math.max(0L, lastSeq - unread);
    }

    public static long localLastReadSeq(long lastMessageSeq, int unreadCount) {
        return Math.max(0L, Math.max(0L, lastMessageSeq) - Math.max(0, unreadCount));
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
        Long seq = message.getSeq();
        return seq != null && seq > localLastReadSeq;
    }

    public static boolean shouldShowDivider(
            int unreadCount,
            boolean dismissed,
            boolean firstUnreadVisible
    ) {
        if (dismissed || unreadCount <= 0 || firstUnreadVisible) {
            return false;
        }
        return true;
    }
}
