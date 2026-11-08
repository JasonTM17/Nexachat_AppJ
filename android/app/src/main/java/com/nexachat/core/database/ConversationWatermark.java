package com.nexachat.core.database;

import androidx.annotation.NonNull;
import java.util.Objects;

/**
 * Watermark pair of conversation ID and its highest known sequence number.
 */
public final class ConversationWatermark {
    @NonNull private final String conversationId;
    private final long maxSeq;

    public ConversationWatermark(@NonNull String conversationId, long maxSeq) {
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.maxSeq = maxSeq;
    }

    @NonNull public String getConversationId() { return conversationId; }
    public long getMaxSeq() { return maxSeq; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ConversationWatermark)) return false;
        ConversationWatermark that = (ConversationWatermark) other;
        return maxSeq == that.maxSeq && Objects.equals(conversationId, that.conversationId);
    }

    @Override public int hashCode() {
        return Objects.hash(conversationId, maxSeq);
    }

    @NonNull @Override public String toString() {
        return "ConversationWatermark(conversationId=" + conversationId + ", maxSeq=" + maxSeq + ')';
    }
}
