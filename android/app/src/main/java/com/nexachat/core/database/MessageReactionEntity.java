package com.nexachat.core.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import java.util.Objects;

/** Aggregated reaction summary for one emoji on one message. */
@Entity(
        tableName = "message_reactions",
        primaryKeys = {"messageId", "emoji"},
        indices = {@Index("conversationId")})
public class MessageReactionEntity {

    @NonNull public String messageId;
    @NonNull public String conversationId;
    @NonNull public String emoji;
    public int count;
    public boolean reactedByMe;

    public MessageReactionEntity(
            @NonNull String messageId,
            @NonNull String conversationId,
            @NonNull String emoji,
            int count,
            boolean reactedByMe) {
        this.messageId = messageId;
        this.conversationId = conversationId;
        this.emoji = emoji;
        this.count = count;
        this.reactedByMe = reactedByMe;
    }

    @NonNull
    public MessageReactionEntity withCount(int nextCount, boolean nextReactedByMe) {
        return new MessageReactionEntity(messageId, conversationId, emoji, nextCount, nextReactedByMe);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof MessageReactionEntity that)) {
            return false;
        }
        return count == that.count
                && reactedByMe == that.reactedByMe
                && messageId.equals(that.messageId)
                && conversationId.equals(that.conversationId)
                && emoji.equals(that.emoji);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, conversationId, emoji, count, reactedByMe);
    }
}
