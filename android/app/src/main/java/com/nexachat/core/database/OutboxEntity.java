package com.nexachat.core.database;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import java.util.Objects;

/** Optimistic-send queue row retained until the matching server acknowledgement. */
@Entity(
        tableName = "outbox",
        indices = {@Index(value = {"ownerUserId", "state", "createdAt"},
                name = "index_outbox_ownerUserId_state_createdAt")})
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
    /** Structured group mention ranges encoded as JSON; [] for legacy outbox rows. */
    @NonNull @ColumnInfo(defaultValue = "'[]'") private final String mentionsJson;
    @ColumnInfo(defaultValue = "0") private final boolean mentionAll;
    @NonNull @ColumnInfo(defaultValue = "''") private final String ownerUserId;
    /** False only while an upgrade-era row may still bind to its stored session owner. */
    @ColumnInfo(defaultValue = "0") private final boolean ownerResolved;

    @Ignore
    public OutboxEntity(
            @NonNull String clientMessageId,
            @NonNull String conversationId,
            @NonNull String type,
            @NonNull String content,
            @Nullable String replyToId,
            @NonNull String state,
            long createdAt,
            @NonNull String attachmentsJson) {
        this(clientMessageId, conversationId, type, content, replyToId, state, createdAt,
                attachmentsJson, "[]", false, "", false);
    }

    @Ignore
    public OutboxEntity(
            @NonNull String clientMessageId,
            @NonNull String conversationId,
            @NonNull String type,
            @NonNull String content,
            @Nullable String replyToId,
            @NonNull String state,
            long createdAt,
            @NonNull String attachmentsJson,
            @NonNull String mentionsJson,
            boolean mentionAll) {
        this(clientMessageId, conversationId, type, content, replyToId, state, createdAt,
                attachmentsJson, mentionsJson, mentionAll, "", false);
    }

    @Ignore
    public OutboxEntity(
            @NonNull String clientMessageId,
            @NonNull String conversationId,
            @NonNull String type,
            @NonNull String content,
            @Nullable String replyToId,
            @NonNull String state,
            long createdAt,
            @NonNull String attachmentsJson,
            @NonNull String mentionsJson,
            boolean mentionAll,
            @NonNull String ownerUserId) {
        this(clientMessageId, conversationId, type, content, replyToId, state, createdAt,
                attachmentsJson, mentionsJson, mentionAll, ownerUserId,
                !ownerUserId.isBlank());
    }

    public OutboxEntity(
            @NonNull String clientMessageId,
            @NonNull String conversationId,
            @NonNull String type,
            @NonNull String content,
            @Nullable String replyToId,
            @NonNull String state,
            long createdAt,
            @NonNull String attachmentsJson,
            @NonNull String mentionsJson,
            boolean mentionAll,
            @NonNull String ownerUserId,
            boolean ownerResolved) {
        this.clientMessageId = clientMessageId;
        this.conversationId = conversationId;
        this.type = type;
        this.content = content;
        this.replyToId = replyToId;
        this.state = state;
        this.createdAt = createdAt;
        this.attachmentsJson = attachmentsJson;
        this.mentionsJson = mentionsJson;
        this.mentionAll = mentionAll;
        this.ownerUserId = Objects.requireNonNull(ownerUserId, "ownerUserId");
        this.ownerResolved = ownerResolved;
    }

    @NonNull public String getClientMessageId() { return clientMessageId; }
    @NonNull public String getConversationId() { return conversationId; }
    @NonNull public String getType() { return type; }
    @NonNull public String getContent() { return content; }
    @Nullable public String getReplyToId() { return replyToId; }
    @NonNull public String getState() { return state; }
    public long getCreatedAt() { return createdAt; }
    @NonNull public String getAttachmentsJson() { return attachmentsJson; }
    @NonNull public String getMentionsJson() { return mentionsJson; }
    public boolean isMentionAll() { return mentionAll; }
    @NonNull public String getOwnerUserId() { return ownerUserId; }
    public boolean isOwnerResolved() { return ownerResolved; }

    /** Returns this row requeued at the supplied FIFO time. */
    @NonNull public OutboxEntity forRetry(long newCreatedAt) {
        return new OutboxEntity(clientMessageId, conversationId, type, content, replyToId,
                OutboxState.SENDING, newCreatedAt, attachmentsJson, mentionsJson, mentionAll,
                ownerUserId, ownerResolved);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof OutboxEntity)) return false;
        OutboxEntity that = (OutboxEntity) other;
        return createdAt == that.createdAt && Objects.equals(clientMessageId, that.clientMessageId)
                && Objects.equals(conversationId, that.conversationId)
                && Objects.equals(type, that.type) && Objects.equals(content, that.content)
                && Objects.equals(replyToId, that.replyToId) && Objects.equals(state, that.state)
                && Objects.equals(attachmentsJson, that.attachmentsJson)
                && Objects.equals(mentionsJson, that.mentionsJson)
                && Objects.equals(ownerUserId, that.ownerUserId)
                && ownerResolved == that.ownerResolved
                && mentionAll == that.mentionAll;
    }

    @Override public int hashCode() {
        return Objects.hash(clientMessageId, conversationId, type, content, replyToId, state,
                createdAt, attachmentsJson, mentionsJson, mentionAll, ownerUserId, ownerResolved);
    }

    @NonNull @Override public String toString() {
        return "OutboxEntity(clientMessageId=" + clientMessageId + ", conversationId="
                + conversationId + ", type=" + type + ", content=" + content + ", replyToId="
                + replyToId + ", state=" + state + ", createdAt=" + createdAt
                + ", attachmentsJson=" + attachmentsJson + ", mentionsJson=" + mentionsJson
                + ", mentionAll=" + mentionAll + ')';
    }
}
