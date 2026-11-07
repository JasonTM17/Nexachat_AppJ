package com.nexachat.core.database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import java.util.Objects;

/** Aggregated reaction summary per message and emoji. */
@Entity(
        tableName = "message_reactions",
        primaryKeys = {"messageId", "emoji"},
        indices = {@Index(value = {"conversationId"})})
public final class MessageReactionEntity {
    @NonNull private final String messageId;
    @NonNull private final String conversationId;
    @NonNull private final String emoji;
    private final int count;
    private final boolean reactedByMe;

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

    @NonNull public String getMessageId() { return messageId; }
    @NonNull public String getConversationId() { return conversationId; }
    @NonNull public String getEmoji() { return emoji; }
    public int getCount() { return count; }
    public boolean getReactedByMe() { return reactedByMe; }

    /** Returns this reaction summary with the optimistic count and ownership state. */
    @NonNull public MessageReactionEntity withReactionState(int newCount, boolean newReactedByMe) {
        return new MessageReactionEntity(messageId, conversationId, emoji, newCount, newReactedByMe);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MessageReactionEntity)) return false;
        MessageReactionEntity that = (MessageReactionEntity) other;
        return count == that.count && reactedByMe == that.reactedByMe
                && Objects.equals(messageId, that.messageId)
                && Objects.equals(conversationId, that.conversationId)
                && Objects.equals(emoji, that.emoji);
    }

    @Override public int hashCode() {
        return Objects.hash(messageId, conversationId, emoji, count, reactedByMe);
    }

    @NonNull @Override public String toString() {
        return "MessageReactionEntity(messageId=" + messageId + ", conversationId="
                + conversationId + ", emoji=" + emoji + ", count=" + count
                + ", reactedByMe=" + reactedByMe + ')';
    }
}
