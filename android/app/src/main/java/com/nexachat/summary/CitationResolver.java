package com.nexachat.summary;

import java.util.Set;

/** Resolves a cited message to a locally available row or an unavailable marker. */
public final class CitationResolver {
    public static final String UNAVAILABLE = "unavailable";

    private CitationResolver() { }

    public static String resolve(String messageId, Set<String> localMessageIds) {
        if (messageId == null || localMessageIds == null || !localMessageIds.contains(messageId)) {
            return UNAVAILABLE;
        }
        return messageId;
    }
}
