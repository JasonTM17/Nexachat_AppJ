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
     * Copy for a surface that still occupies space after a delete or crypto
     * placeholder: tombstones win, then the encrypted placeholder, then the
     * live preview. Empty live content must not fall through to a raw type
     * token such as {@code TEXT} when the row is already a tombstone.
     */
    public static String resolvePlaceholderPreview(
            boolean tombstone,
            boolean encryptedPlaceholder,
            String livePreview,
            String emptyFallback,
            String deletedLabel,
            String e2eePlaceholder
    ) {
        if (tombstone) {
            return deletedLabel;
        }
        if (encryptedPlaceholder) {
            return e2eePlaceholder;
        }
        if (livePreview == null || livePreview.isEmpty()) {
            return emptyFallback == null ? "" : emptyFallback;
        }
        return livePreview;
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
            return firstCodePoint(parts[0]).toUpperCase(java.util.Locale.ROOT);
        }
        String first = firstCodePoint(parts[0]).toUpperCase(java.util.Locale.ROOT);
        String last = firstCodePoint(parts[parts.length - 1]).toUpperCase(java.util.Locale.ROOT);
        return first + last;
    }

    /** First Unicode code point of a word — substring(0, 1) would split surrogate pairs. */
    private static String firstCodePoint(String word) {
        return word.substring(0, word.offsetByCodePoints(0, 1));
    }
}
