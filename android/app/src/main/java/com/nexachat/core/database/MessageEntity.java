package com.nexachat.core.database;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;
import java.util.Objects;

/**
 * Room entity for persisted message history. Room is the source of truth:
 * rendering reads these rows so a process restart shows history immediately.
 *
 * <p>This mirrors the server {@code messages} row plus a denormalized sender
 * display name for list rendering. Ordering is by server-assigned {@code seq},
 * never by client clock.
 */
@Entity(tableName = "messages", indices = {@Index(value = {"conversationId", "seq"})})
public final class MessageEntity {
    @PrimaryKey @NonNull private final String id;
    @NonNull private final String conversationId;
    private final long seq;
    @NonNull private final String senderId;
    @Nullable private final String senderDisplayName;
    @NonNull private final String type;
    @Nullable private final String content;
    @Nullable private final String clientMessageId;
    @Nullable private final String replyToId;
    /** Denormalized embedded reply preview, retained beyond the loaded history window. */
    @Nullable private final String replyToSenderName;
    @Nullable private final String replyToSnippet;
    /** Epoch milliseconds converted from the DTO's ISO instant. */
    private final long createdAt;
    /** Re-encoded attachments array JSON; null when empty. */
    @Nullable private final String attachmentsJson;
    /** Null while the content is original. */
    @Nullable private final Long editedAtEpochMs;
    /** Soft-delete marker; deleted rows retain seq for history continuity. */
    @Nullable private final Long deletedAtEpochMs;
    /** Structured group mention ranges encoded as JSON; [] for legacy messages. */
    @NonNull @ColumnInfo(defaultValue = "'[]'") private final String mentionsJson;
    @ColumnInfo(defaultValue = "0") private final boolean mentionAll;

    @ColumnInfo(defaultValue = "0") private final long changeVersion;
    @ColumnInfo(defaultValue = "0") private final long generation;

    @Ignore
    public MessageEntity(
            @NonNull String id,
            @NonNull String conversationId,
            long seq,
            @NonNull String senderId,
            @Nullable String senderDisplayName,
            @NonNull String type,
            @Nullable String content,
            @Nullable String clientMessageId,
            @Nullable String replyToId,
            @Nullable String replyToSenderName,
            @Nullable String replyToSnippet,
            long createdAt,
            @Nullable String attachmentsJson,
            @Nullable Long editedAtEpochMs,
            @Nullable Long deletedAtEpochMs) {
        this(id, conversationId, seq, senderId, senderDisplayName, type, content,
                clientMessageId, replyToId, replyToSenderName, replyToSnippet, createdAt,
                attachmentsJson, editedAtEpochMs, deletedAtEpochMs, "[]", false);
    }

    @Ignore
    public MessageEntity(
            @NonNull String id,
            @NonNull String conversationId,
            long seq,
            @NonNull String senderId,
            @Nullable String senderDisplayName,
            @NonNull String type,
            @Nullable String content,
            @Nullable String clientMessageId,
            @Nullable String replyToId,
            @Nullable String replyToSenderName,
            @Nullable String replyToSnippet,
            long createdAt,
            @Nullable String attachmentsJson,
            @Nullable Long editedAtEpochMs,
            @Nullable Long deletedAtEpochMs,
            @NonNull String mentionsJson,
            boolean mentionAll) {
        this(id, conversationId, seq, senderId, senderDisplayName, type, content,
                clientMessageId, replyToId, replyToSenderName, replyToSnippet, createdAt,
                attachmentsJson, editedAtEpochMs, deletedAtEpochMs, mentionsJson, mentionAll, 0L, 0L);
    }

    public MessageEntity(@NonNull String id, @NonNull String conversationId, long seq,
            @NonNull String senderId, @Nullable String senderDisplayName, @NonNull String type,
            @Nullable String content, @Nullable String clientMessageId, @Nullable String replyToId,
            @Nullable String replyToSenderName, @Nullable String replyToSnippet, long createdAt,
            @Nullable String attachmentsJson, @Nullable Long editedAtEpochMs,
            @Nullable Long deletedAtEpochMs, @NonNull String mentionsJson, boolean mentionAll,
            long changeVersion, long generation) {
        this.changeVersion = changeVersion;
        this.generation = generation;
        this.id = id;
        this.conversationId = conversationId;
        this.seq = seq;
        this.senderId = senderId;
        this.senderDisplayName = senderDisplayName;
        this.type = type;
        this.content = content;
        this.clientMessageId = clientMessageId;
        this.replyToId = replyToId;
        this.replyToSenderName = replyToSenderName;
        this.replyToSnippet = replyToSnippet;
        this.createdAt = createdAt;
        this.attachmentsJson = attachmentsJson;
        this.editedAtEpochMs = editedAtEpochMs;
        this.deletedAtEpochMs = deletedAtEpochMs;
        this.mentionsJson = mentionsJson;
        this.mentionAll = mentionAll;
    }

    @NonNull public String getId() { return id; }
    @NonNull public String getConversationId() { return conversationId; }
    public long getSeq() { return seq; }
    @NonNull public String getSenderId() { return senderId; }
    @Nullable public String getSenderDisplayName() { return senderDisplayName; }
    @NonNull public String getType() { return type; }
    @Nullable public String getContent() { return content; }
    @Nullable public String getClientMessageId() { return clientMessageId; }
    @Nullable public String getReplyToId() { return replyToId; }
    @Nullable public String getReplyToSenderName() { return replyToSenderName; }
    @Nullable public String getReplyToSnippet() { return replyToSnippet; }
    public long getCreatedAt() { return createdAt; }
    @Nullable public String getAttachmentsJson() { return attachmentsJson; }
    @Nullable public Long getEditedAtEpochMs() { return editedAtEpochMs; }
    @Nullable public Long getDeletedAtEpochMs() { return deletedAtEpochMs; }
    @NonNull public String getMentionsJson() { return mentionsJson; }
    public boolean isMentionAll() { return mentionAll; }

    public long getChangeVersion() { return changeVersion; }
    public long getGeneration() { return generation; }

    @NonNull public MessageEntity withSyncVersion(long version, long nextGeneration) {
        return new MessageEntity(id, conversationId, seq, senderId, senderDisplayName, type,
                content, clientMessageId, replyToId, replyToSenderName, replyToSnippet, createdAt,
                attachmentsJson, editedAtEpochMs, deletedAtEpochMs, mentionsJson, mentionAll,
                version, nextGeneration);
    }

    /** Returns this row with the edited text and edit time replaced. */
    @NonNull public MessageEntity withContentAndEditedAt(
            @Nullable String newContent, @Nullable Long newEditedAtEpochMs) {
        return new MessageEntity(id, conversationId, seq, senderId, senderDisplayName, type,
                newContent, clientMessageId, replyToId, replyToSenderName, replyToSnippet,
                createdAt, attachmentsJson, newEditedAtEpochMs, deletedAtEpochMs,
                mentionsJson, mentionAll, changeVersion, generation);
    }

    /** Returns the neutral tombstone form while retaining message identity and ordering. */
    @NonNull public MessageEntity asDeleted(@Nullable Long newDeletedAtEpochMs) {
        return new MessageEntity(id, conversationId, seq, senderId, senderDisplayName, type,
                "", clientMessageId, replyToId, replyToSenderName, replyToSnippet, createdAt,
                null, editedAtEpochMs, newDeletedAtEpochMs, "[]", false, changeVersion, generation);
    }

    /** Returns this row with only its edit timestamp replaced. */
    @NonNull public MessageEntity withEditedAtEpochMs(@Nullable Long newEditedAtEpochMs) {
        return new MessageEntity(id, conversationId, seq, senderId, senderDisplayName, type,
                content, clientMessageId, replyToId, replyToSenderName, replyToSnippet, createdAt,
                attachmentsJson, newEditedAtEpochMs, deletedAtEpochMs, mentionsJson, mentionAll, changeVersion, generation);
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof MessageEntity)) return false;
        MessageEntity that = (MessageEntity) other;
        return seq == that.seq && createdAt == that.createdAt
                && changeVersion == that.changeVersion && generation == that.generation
                && Objects.equals(id, that.id)
                && Objects.equals(conversationId, that.conversationId)
                && Objects.equals(senderId, that.senderId)
                && Objects.equals(senderDisplayName, that.senderDisplayName)
                && Objects.equals(type, that.type)
                && Objects.equals(content, that.content)
                && Objects.equals(clientMessageId, that.clientMessageId)
                && Objects.equals(replyToId, that.replyToId)
                && Objects.equals(replyToSenderName, that.replyToSenderName)
                && Objects.equals(replyToSnippet, that.replyToSnippet)
                && Objects.equals(attachmentsJson, that.attachmentsJson)
                && Objects.equals(editedAtEpochMs, that.editedAtEpochMs)
                && Objects.equals(deletedAtEpochMs, that.deletedAtEpochMs)
                && Objects.equals(mentionsJson, that.mentionsJson)
                && mentionAll == that.mentionAll;
    }

    @Override public int hashCode() {
        return Objects.hash(id, conversationId, seq, senderId, senderDisplayName, type, content,
                clientMessageId, replyToId, replyToSenderName, replyToSnippet, createdAt,
                attachmentsJson, editedAtEpochMs, deletedAtEpochMs, mentionsJson, mentionAll, changeVersion, generation);
    }

    @NonNull @Override public String toString() {
        return "MessageEntity(id=" + id + ", conversationId=" + conversationId + ", seq=" + seq
                + ", senderId=" + senderId + ", senderDisplayName=" + senderDisplayName
                + ", type=" + type + ", content=" + content + ", clientMessageId="
                + clientMessageId + ", replyToId=" + replyToId + ", replyToSenderName="
                + replyToSenderName + ", replyToSnippet=" + replyToSnippet + ", createdAt="
                + createdAt + ", attachmentsJson=" + attachmentsJson + ", editedAtEpochMs="
                + editedAtEpochMs + ", deletedAtEpochMs=" + deletedAtEpochMs
                + ", mentionsJson=" + mentionsJson + ", mentionAll=" + mentionAll + ')';
    }
}
