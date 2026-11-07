package com.nexachat.data;

import androidx.annotation.Nullable;
import java.util.Objects;

/** Read-watermark event for the open conversation (read receipts, P2). */
public final class ReadEvent {
    private final String conversationId;
    @Nullable private final String userId;
    @Nullable private final Long upToSeq;

    public ReadEvent(String conversationId, @Nullable String userId, @Nullable Long upToSeq) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.upToSeq = upToSeq;
    }
    public String getConversationId() { return conversationId; }
    @Nullable public String getUserId() { return userId; }
    @Nullable public Long getUpToSeq() { return upToSeq; }

    @Override public boolean equals(Object other) {
        if (!(other instanceof ReadEvent that)) return false;
        return Objects.equals(conversationId, that.conversationId)
                && Objects.equals(userId, that.userId) && Objects.equals(upToSeq, that.upToSeq);
    }

    @Override public int hashCode() { return Objects.hash(conversationId, userId, upToSeq); }
}
