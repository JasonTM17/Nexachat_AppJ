package com.nexachat.data;

import androidx.annotation.Nullable;
import com.nexachat.core.network.dto.AttachmentDto;
import java.util.List;
import java.util.Objects;

/**
 * Message as the feature layer sees it, merging a Room row (SENT) and an
 * outbox row (SENDING or FAILED) into one shape with {@code mine} resolved.
 */
public final class ChatMessage {
    /** Server message id, or clientMessageId while pending. */
    private final String id;
    private final String conversationId;
    @Nullable private final Long seq;
    private final String senderId;
    @Nullable private final String senderDisplayName;
    private final String type;
    @Nullable private final String content;
    @Nullable private final String replyToId;
    /** Embedded reply preview fields survive while the original is unloaded. */
    @Nullable private final String replyToSenderName;
    @Nullable private final String replyToSnippet;
    private final long createdAtEpochMs;
    @Nullable private final String clientMessageId;
    private final DeliveryStatus status;
    private final boolean mine;
    /** Parsed attachments; empty for text-only messages. */
    private final List<AttachmentDto> attachments;
    /** Aggregated emoji chips rendered under the bubble. */
    private final List<ReactionChip> reactions;
    /** Null while the content is original. */
    @Nullable private final Long editedAtEpochMs;
    /** Soft-delete marker; deleted rows render a neutral placeholder. */
    @Nullable private final Long deletedAtEpochMs;

    public ChatMessage(String id, String conversationId, @Nullable Long seq, String senderId,
            @Nullable String senderDisplayName, String type, @Nullable String content,
            @Nullable String replyToId, @Nullable String replyToSenderName,
            @Nullable String replyToSnippet, long createdAtEpochMs,
            @Nullable String clientMessageId, DeliveryStatus status, boolean mine,
            List<AttachmentDto> attachments, List<ReactionChip> reactions,
            @Nullable Long editedAtEpochMs, @Nullable Long deletedAtEpochMs) {
        this.id = Objects.requireNonNull(id, "id");
        this.conversationId = Objects.requireNonNull(conversationId, "conversationId");
        this.seq = seq;
        this.senderId = Objects.requireNonNull(senderId, "senderId");
        this.senderDisplayName = senderDisplayName;
        this.type = Objects.requireNonNull(type, "type");
        this.content = content;
        this.replyToId = replyToId;
        this.replyToSenderName = replyToSenderName;
        this.replyToSnippet = replyToSnippet;
        this.createdAtEpochMs = createdAtEpochMs;
        this.clientMessageId = clientMessageId;
        this.status = Objects.requireNonNull(status, "status");
        this.mine = mine;
        this.attachments = List.copyOf(attachments);
        this.reactions = List.copyOf(reactions);
        this.editedAtEpochMs = editedAtEpochMs;
        this.deletedAtEpochMs = deletedAtEpochMs;
    }

    public String getId() { return id; }
    public String getConversationId() { return conversationId; }
    @Nullable public Long getSeq() { return seq; }
    public String getSenderId() { return senderId; }
    @Nullable public String getSenderDisplayName() { return senderDisplayName; }
    public String getType() { return type; }
    @Nullable public String getContent() { return content; }
    @Nullable public String getReplyToId() { return replyToId; }
    @Nullable public String getReplyToSenderName() { return replyToSenderName; }
    @Nullable public String getReplyToSnippet() { return replyToSnippet; }
    public long getCreatedAtEpochMs() { return createdAtEpochMs; }
    @Nullable public String getClientMessageId() { return clientMessageId; }
    public DeliveryStatus getStatus() { return status; }
    public boolean getMine() { return mine; }
    public List<AttachmentDto> getAttachments() { return attachments; }
    public List<ReactionChip> getReactions() { return reactions; }
    @Nullable public Long getEditedAtEpochMs() { return editedAtEpochMs; }
    @Nullable public Long getDeletedAtEpochMs() { return deletedAtEpochMs; }
    /** True when this message is a tombstone with content and attachments stripped. */
    public boolean isDeleted() { return deletedAtEpochMs != null; }

    @Override public boolean equals(Object other) {
        if (!(other instanceof ChatMessage that)) return false;
        return createdAtEpochMs == that.createdAtEpochMs && mine == that.mine
                && Objects.equals(id, that.id) && Objects.equals(conversationId, that.conversationId)
                && Objects.equals(seq, that.seq) && Objects.equals(senderId, that.senderId)
                && Objects.equals(senderDisplayName, that.senderDisplayName)
                && Objects.equals(type, that.type) && Objects.equals(content, that.content)
                && Objects.equals(replyToId, that.replyToId)
                && Objects.equals(replyToSenderName, that.replyToSenderName)
                && Objects.equals(replyToSnippet, that.replyToSnippet)
                && Objects.equals(clientMessageId, that.clientMessageId)
                && status == that.status && Objects.equals(attachments, that.attachments)
                && Objects.equals(reactions, that.reactions)
                && Objects.equals(editedAtEpochMs, that.editedAtEpochMs)
                && Objects.equals(deletedAtEpochMs, that.deletedAtEpochMs);
    }
    @Override public int hashCode() {
        return Objects.hash(id, conversationId, seq, senderId, senderDisplayName, type, content,
                replyToId, replyToSenderName, replyToSnippet, createdAtEpochMs, clientMessageId,
                status, mine, attachments, reactions, editedAtEpochMs, deletedAtEpochMs);
    }
}
