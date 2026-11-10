package com.nexachat.feature.inbox;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.data.ConversationSummary;
import com.nexachat.data.PeerPresence;

/**
 * Pure presentation and gesture rules for inbox rows: unread badge formatting,
 * presence status resolution, and horizontal swipe actions.
 */
public final class InboxBadgeRules {

    public static final int MAX_VISIBLE_UNREAD_COUNT = 99;
    public static final String OVERFLOW_UNREAD_BADGE = "99+";

    public enum SwipeAction {
        NONE,
        PIN,
        UNPIN,
        ARCHIVE,
        UNARCHIVE
    }

    private InboxBadgeRules() {}

    @NonNull
    public static String formatUnreadCount(int unreadCount) {
        if (unreadCount <= 0) {
            return "";
        }
        if (unreadCount > MAX_VISIBLE_UNREAD_COUNT) {
            return OVERFLOW_UNREAD_BADGE;
        }
        return String.valueOf(unreadCount);
    }

    @Nullable
    public static Boolean resolveOnlineStatus(@Nullable ConversationSummary conversation) {
        if (conversation == null || conversation.isGroup()) {
            return null;
        }
        PeerPresence presence = conversation.getPeerPresence();
        if (presence == null) {
            return null;
        }
        return Boolean.valueOf(presence.getOnline());
    }

    @NonNull
    public static SwipeAction resolveStartSwipeAction(@Nullable ConversationSummary conversation) {
        if (conversation == null) {
            return SwipeAction.NONE;
        }
        return conversation.isPinned() ? SwipeAction.UNPIN : SwipeAction.PIN;
    }

    @NonNull
    public static SwipeAction resolveEndSwipeAction(@Nullable ConversationSummary conversation) {
        if (conversation == null) {
            return SwipeAction.NONE;
        }
        return conversation.isArchived() ? SwipeAction.UNARCHIVE : SwipeAction.ARCHIVE;
    }
}
