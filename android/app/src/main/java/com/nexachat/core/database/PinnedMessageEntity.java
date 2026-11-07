package com.nexachat.core.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import java.util.Objects;

/** One pinned message per conversation; pinnedAt sorts the pin banner. */
@Entity(tableName = "pinned_messages", primaryKeys = {"conversationId", "messageId"})
public final class PinnedMessageEntity {
    @NonNull private final String conversationId;
    @NonNull private final String messageId;
    private final long pinnedAt;

    public PinnedMessageEntity(
            @NonNull String conversationId, @NonNull String messageId, long pinnedAt) {
        this.conversationId = conversationId;
        this.messageId = messageId;
        this.pinnedAt = pinnedAt;
    }

    @NonNull public String getConversationId() { return conversationId; }
    @NonNull public String getMessageId() { return messageId; }
    public long getPinnedAt() { return pinnedAt; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PinnedMessageEntity)) return false;
        PinnedMessageEntity that = (PinnedMessageEntity) other;
        return pinnedAt == that.pinnedAt && Objects.equals(conversationId, that.conversationId)
                && Objects.equals(messageId, that.messageId);
    }

    @Override public int hashCode() {
        return Objects.hash(conversationId, messageId, pinnedAt);
    }

    @NonNull @Override public String toString() {
        return "PinnedMessageEntity(conversationId=" + conversationId + ", messageId="
                + messageId + ", pinnedAt=" + pinnedAt + ')';
    }
}
