package com.nexachat.core.ui;

/**
 * Pure presentation logic for forward target conversation filtering (F5).
 * Written in Java per the project owner language rule.
 */
public final class ForwardFilter {

    private ForwardFilter() {}

    /**
     * Checks if a conversation title matches the user's forward search query
     * ignoring case and diacritics.
     */
    public static boolean matchesQuery(String conversationTitle, String query) {
        if (query == null || query.trim().isEmpty()) {
            return true;
        }
        if (conversationTitle == null || conversationTitle.isEmpty()) {
            return false;
        }
        String foldedTitle = SearchHighlightHelper.foldToAscii(conversationTitle);
        String foldedQuery = SearchHighlightHelper.foldToAscii(query.trim());
        return foldedTitle.contains(foldedQuery);
    }

    /**
     * Checks if a message can be forwarded.
     * Forwarding is TEXT only: attachments and deleted messages cannot be forwarded.
     */
    public static boolean canForward(String type, boolean isDeleted) {
        if (isDeleted) {
            return false;
        }
        return "TEXT".equalsIgnoreCase(type);
    }
}
