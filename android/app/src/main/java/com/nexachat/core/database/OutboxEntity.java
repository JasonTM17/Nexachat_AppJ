package com.nexachat.core.database;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import java.util.Objects;

/** Optimistic-send queue row retained until the matching server acknowledgement. */
@Entity(tableName = "outbox")
public final class OutboxEntity {
    @PrimaryKey @NonNull private final String clientMessageId;
    @NonNull private final String conversationId;
    @NonNull private final String type;
    @NonNull private final String content;
    @Nullable private final String replyToId;
    /** {@link OutboxState#SENDING} or {@link OutboxState#FAILED}. */
    @NonNull private final String state;
    /** Client clock used only to order the pending queue. */
    private final long createdAt;
    /** Re-encoded attachments JSON, empty when no attachments exist. */
    @NonNull private final String attachmentsJson;

    public OutboxEntity(
            @NonNull String clientMessageId,
            @NonNull String conversationId,
            @NonNull String type,
            @NonNull String content,
            @Nullable String replyToId,
            @NonNull String state,
            long createdAt,
            @NonNull String attachmentsJson) {
        this.clientMessageId = clientMessageId;
        this.conversationId = conversationId;
        this.type = type;
        this.content = content;
        this.replyToId = replyToId;
        this.state = state;
        this.createdAt = createdAt;
        this.attachmentsJson = attachmentsJson;
    }

    @NonNull public String getClientMessageId() { return clientMessageId; }
    @NonNull public String getConversationId() { return conversationId; }
    @NonNull public String getType() { return type; }
    @NonNull public String getContent() { return content; }
    @Nullable public String getReplyToId() { return replyToId; }
    @NonNull public String getState() { return state; }
    public long getCreatedAt() { return createdAt; }
    @NonNull public String getAttachmentsJson() { return attachmentsJson; }

    /** Returns this row requeued at the supplied FIFO time. */
    @NonNull public OutboxEntity forRetry(long newCreatedAt) {
        return new OutboxEntity(clientMessageId, conversationId, type, content, replyToId,
                OutboxState.SENDING, newCreatedAt, attachmentsJson);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof OutboxEntity)) return false;
        OutboxEntity that = (OutboxEntity) other;
        return createdAt == that.createdAt && Objects.equals(clientMessageId, that.clientMessageId)
                && Objects.equals(conversationId, that.conversationId)
                && Objects.equals(type, that.type) && Objects.equals(content, that.content)
                && Objects.equals(replyToId, that.replyToId) && Objects.equals(state, that.state)
                && Objects.equals(attachmentsJson, that.attachmentsJson);
    }

    @Override public int hashCode() {
        return Objects.hash(clientMessageId, conversationId, type, content, replyToId, state,
                createdAt, attachmentsJson);
    }

    @NonNull @Override public String toString() {
        return "OutboxEntity(clientMessageId=" + clientMessageId + ", conversationId="
                + conversationId + ", type=" + type + ", content=" + content + ", replyToId="
                + replyToId + ", state=" + state + ", createdAt=" + createdAt
                + ", attachmentsJson=" + attachmentsJson + ')';
    }
}
