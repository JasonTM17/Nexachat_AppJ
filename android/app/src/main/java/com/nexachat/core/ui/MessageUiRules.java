package com.nexachat.core.ui;

/**
 * Pure presentation logic for message state, action eligibility, and avatar initials.
 * Written in Java per the project owner language rule.
 */
public final class MessageUiRules {

    private MessageUiRules() {}

    /**
     * Determines whether a message is in a tombstone (deleted) state.
     */
    public static boolean isTombstone(boolean isDeleted, Long deletedAtEpochMs) {
        return isDeleted || (deletedAtEpochMs != null && deletedAtEpochMs > 0L);
    }

    /**
     * Determines whether the "Edited" / "Đã chỉnh sửa" label should be displayed.
     * Only shown if editedAt is present AND message is not deleted.
     */
    public static boolean isEdited(Long editedAtEpochMs, Long deletedAtEpochMs) {
        return (editedAtEpochMs != null && editedAtEpochMs > 0L) &&
                (deletedAtEpochMs == null || deletedAtEpochMs <= 0L);
    }

    /**
     * Checks if a message can be edited: author only, TEXT only, not deleted.
     */
    public static boolean canEdit(boolean isMine, String type, boolean isDeleted) {
        return isMine && !isDeleted && "TEXT".equalsIgnoreCase(type);
    }

    /**
     * Checks if a message can be deleted: author only, not already deleted.
     */
    public static boolean canDelete(boolean isMine, boolean isDeleted) {
        return isMine && !isDeleted;
    }

    /**
     * Checks if message content can be copied to clipboard: TEXT only, not deleted.
     */
    public static boolean canCopy(String type, boolean isDeleted) {
        return !isDeleted && "TEXT".equalsIgnoreCase(type);
    }

    /**
     * Checks if message can be replied to: not deleted.
     */
    public static boolean canReply(boolean isDeleted) {
        return !isDeleted;
    }

    /**
     * Extracts initials for users and groups.
     * Single word -> 1 letter. Multiple words -> first letter of first word + first letter of last word.
     * Empty or blank -> "?".
     */
    public static String extractInitials(String name) {
        if (name == null) {
            return "?";
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty()) {
            return "?";
        }
        String[] parts = trimmed.split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return "?";
        }
        if (parts.length == 1) {
            return parts[0].substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        }
        String first = parts[0].substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        String last = parts[parts.length - 1].substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        return first + last;
    }
}
