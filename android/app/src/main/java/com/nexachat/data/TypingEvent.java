package com.nexachat.data;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Peer typing state forwarded to the open chat screen with an optional absolute expiry. */
public final class TypingEvent {
    private final String conversationId;
    @Nullable private final String userId;
    @Nullable private final String displayName;
    private final boolean active;
    @Nullable private final Long expiresAtEpochMs;

    public TypingEvent(String conversationId, @Nullable String userId, @Nullable String displayName) {
        this(conversationId, userId, displayName, true, null);
    }

    public TypingEvent(
            String conversationId,
            @Nullable String userId,
            @Nullable String displayName,
            boolean active,
            @Nullable Long expiresAtEpochMs) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.displayName = displayName;
        this.active = active;
        this.expiresAtEpochMs = expiresAtEpochMs;
    }
    public String getConversationId() { return conversationId; }
    @Nullable public String getUserId() { return userId; }
    @Nullable public String getDisplayName() { return displayName; }
    public boolean isActive() { return active; }
    @Nullable public Long getExpiresAtEpochMs() { return expiresAtEpochMs; }

    @Override public boolean equals(Object other) {
        if (!(other instanceof TypingEvent that)) return false;
        return Objects.equals(conversationId, that.conversationId)
                && Objects.equals(userId, that.userId)
                && Objects.equals(displayName, that.displayName)
                && active == that.active
                && Objects.equals(expiresAtEpochMs, that.expiresAtEpochMs);
    }

    @Override public int hashCode() {
        return Objects.hash(conversationId, userId, displayName, active, expiresAtEpochMs);
    }
}
