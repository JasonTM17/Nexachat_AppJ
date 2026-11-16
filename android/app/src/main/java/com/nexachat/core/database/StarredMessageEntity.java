package com.nexachat.core.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Caller-owned star marker for a message. Mirrors the server `message_stars`
 * row locally so bubbles and the action sheet can show star state without a
 * per-message round trip. Rows are keyed by message id because a message id
 * already implies its conversation.
 */
@Entity(tableName = "message_stars", indices = {@Index("conversationId")})
public final class StarredMessageEntity {
    @PrimaryKey @NonNull private final String messageId;
    @NonNull private final String conversationId;
    /** Epoch milliseconds when the caller starred the message. */
    private final long starredAt;

    public StarredMessageEntity(
            @NonNull String messageId,
            @NonNull String conversationId,
            long starredAt) {
        this.messageId = messageId;
        this.conversationId = conversationId;
        this.starredAt = starredAt;
    }

    @NonNull public String getMessageId() {
        return messageId;
    }

    @NonNull public String getConversationId() {
        return conversationId;
    }

    public long getStarredAt() {
        return starredAt;
    }
}
