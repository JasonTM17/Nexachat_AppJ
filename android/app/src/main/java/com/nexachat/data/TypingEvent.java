package com.nexachat.data;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Peer-is-typing signal forwarded to the open chat screen, where a 5 second TTL is applied. */
public final class TypingEvent {
    private final String conversationId;
    @Nullable private final String userId;
    @Nullable private final String displayName;

    public TypingEvent(String conversationId, @Nullable String userId, @Nullable String displayName) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.displayName = displayName;
    }
    public String getConversationId() { return conversationId; }
    @Nullable public String getUserId() { return userId; }
    @Nullable public String getDisplayName() { return displayName; }

    @Override public boolean equals(Object other) {
        if (!(other instanceof TypingEvent that)) return false;
        return Objects.equals(conversationId, that.conversationId)
                && Objects.equals(userId, that.userId) && Objects.equals(displayName, that.displayName);
    }

    @Override public int hashCode() { return Objects.hash(conversationId, userId, displayName); }
}
