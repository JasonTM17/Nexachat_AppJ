package com.nexachat.core.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import java.util.Objects;

/** One pinned message. pinnedAt only orders the banner, not chat history. */
@Entity(tableName = "pinned_messages", primaryKeys = {"conversationId", "messageId"})
public class PinnedMessageEntity {

    @NonNull public String conversationId;
    @NonNull public String messageId;
    public long pinnedAt;

    public PinnedMessageEntity(
            @NonNull String conversationId, @NonNull String messageId, long pinnedAt) {
        this.conversationId = conversationId;
        this.messageId = messageId;
        this.pinnedAt = pinnedAt;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof PinnedMessageEntity that)) {
            return false;
        }
        return pinnedAt == that.pinnedAt
                && conversationId.equals(that.conversationId)
                && messageId.equals(that.messageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(conversationId, messageId, pinnedAt);
    }
}
