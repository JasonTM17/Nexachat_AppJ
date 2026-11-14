package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import androidx.annotation.NonNull;
import java.util.List;
import java.util.Objects;

/** Reflective Moshi bean. Absent JSON keeps the no-arg defaults. */
public final class MessageDto {

    public static final String TYPE_TEXT = "TEXT";
    public static final String TYPE_IMAGE = "IMAGE";
    public static final String TYPE_VIDEO = "VIDEO";
    public static final String TYPE_AUDIO = "AUDIO";
    public static final String TYPE_FILE = "FILE";

    private final String id;
    @Nullable private final Long seq;
    @Nullable private final String conversationId;
    @Nullable private final String senderId;
    @Nullable private final String senderDisplayName;
    @Nullable private final SenderRefDto sender;
    @Nullable private final String clientMessageId;
    @Nullable private final String type;
    @Nullable private final String content;
    @Nullable private final ReplyRefDto replyTo;
    @Nullable private final String replyToId;
    @Nullable private final List<ReactionSummaryDto> reactions;
    @Nullable private final List<AttachmentDto> attachments;
    @Nullable private final String createdAt;
    @Nullable private final String editedAt;
    @Nullable private final String deletedAt;
    @Nullable private final List<MessageMention> mentions;
    private final boolean mentionAll;
    @Nullable private final String encryption;
    @Nullable private final List<E2eeEnvelopeDto> e2eeEnvelopes;
    @Nullable private Long changeVersion;
    @Nullable private Long generation;

    @Nullable public Long getChangeVersion() { return changeVersion; }
    @Nullable public Long getGeneration() { return generation; }

    public MessageDto() {
        this(null, null, null, null, null, null, null, null, null, null, null, null,
                List.of(), null, null, null, List.of(), false);
    }

    public MessageDto(@NonNull String id, @Nullable Long seq, @Nullable String conversationId, @Nullable String senderId, @Nullable String senderDisplayName, @Nullable SenderRefDto sender, @Nullable String clientMessageId, @Nullable String type, @Nullable String content, @Nullable ReplyRefDto replyTo, @Nullable String replyToId, @Nullable List<ReactionSummaryDto> reactions, @Nullable List<AttachmentDto> attachments, @Nullable String createdAt, @Nullable String editedAt, @Nullable String deletedAt) {
        this(id, seq, conversationId, senderId, senderDisplayName, sender, clientMessageId,
                type, content, replyTo, replyToId, reactions, attachments, createdAt, editedAt,
                deletedAt, List.of(), false);
    }

    public MessageDto(@NonNull String id, @Nullable Long seq, @Nullable String conversationId,
            @Nullable String senderId, @Nullable String senderDisplayName,
            @Nullable SenderRefDto sender, @Nullable String clientMessageId,
            @Nullable String type, @Nullable String content, @Nullable ReplyRefDto replyTo,
            @Nullable String replyToId, @Nullable List<ReactionSummaryDto> reactions,
            @Nullable List<AttachmentDto> attachments, @Nullable String createdAt,
            @Nullable String editedAt, @Nullable String deletedAt,
            @Nullable List<MessageMention> mentions, boolean mentionAll) {
        this(id, seq, conversationId, senderId, senderDisplayName, sender, clientMessageId,
                type, content, replyTo, replyToId, reactions, attachments, createdAt, editedAt,
                deletedAt, mentions, mentionAll, null, null);
    }

    public MessageDto(@NonNull String id, @Nullable Long seq, @Nullable String conversationId,
            @Nullable String senderId, @Nullable String senderDisplayName,
            @Nullable SenderRefDto sender, @Nullable String clientMessageId,
            @Nullable String type, @Nullable String content, @Nullable ReplyRefDto replyTo,
            @Nullable String replyToId, @Nullable List<ReactionSummaryDto> reactions,
            @Nullable List<AttachmentDto> attachments, @Nullable String createdAt,
            @Nullable String editedAt, @Nullable String deletedAt,
            @Nullable List<MessageMention> mentions, boolean mentionAll,
            @Nullable String encryption, @Nullable List<E2eeEnvelopeDto> e2eeEnvelopes) {
        this.encryption = encryption;
        this.e2eeEnvelopes = e2eeEnvelopes == null ? List.of() : List.copyOf(e2eeEnvelopes);
        this.id = id;
        this.seq = seq;
        this.conversationId = conversationId;
        this.senderId = senderId;
        this.senderDisplayName = senderDisplayName;
        this.sender = sender;
        this.clientMessageId = clientMessageId;
        this.type = type;
        this.content = content;
        this.replyTo = replyTo;
        this.replyToId = replyToId;
        this.reactions = reactions;
        this.attachments = attachments == null ? List.of() : attachments;
        this.createdAt = createdAt;
        this.editedAt = editedAt;
        this.deletedAt = deletedAt;
        this.mentions = mentions == null ? List.of() : List.copyOf(mentions);
        this.mentionAll = mentionAll;
    }

    public MessageDto(String id, Long seq, String conversationId, String senderId, String clientMessageId, String type, String content, String createdAt) {
        this(id, seq, conversationId, senderId, null, null, clientMessageId, type, content, null, null, null, java.util.List.of(), createdAt, null, null);
    }

    public MessageDto(String id, Long seq, String conversationId, String senderId, String type, String content, String createdAt) {
        this(id, seq, conversationId, senderId, null, null, null, type, content, null, null, null, java.util.List.of(), createdAt, null, null);
    }

    public MessageDto(String id, Long seq, String conversationId, String senderId, String type, String content, ReplyRefDto replyTo, java.util.List<ReactionSummaryDto> reactions, String createdAt) {
        this(id, seq, conversationId, senderId, null, null, null, type, content, replyTo, null, reactions, java.util.List.of(), createdAt, null, null);
    }

    @NonNull public String getId() {
        return id;
    }

    @Nullable public Long getSeq() {
        return seq;
    }

    @Nullable public String getConversationId() {
        return conversationId;
    }

    @Nullable public String getSenderId() {
        return senderId;
    }

    @Nullable public String getSenderDisplayName() {
        return senderDisplayName;
    }

    @Nullable public SenderRefDto getSender() {
        return sender;
    }

    @Nullable public String getClientMessageId() {
        return clientMessageId;
    }

    @Nullable public String getType() {
        return type;
    }

    @Nullable public String getContent() {
        return content;
    }

    @Nullable public ReplyRefDto getReplyTo() {
        return replyTo;
    }

    @Nullable public String getReplyToId() {
        return replyToId;
    }

    @Nullable public List<ReactionSummaryDto> getReactions() {
        return reactions;
    }

    @NonNull public List<AttachmentDto> getAttachments() {
        return attachments;
    }

    @Nullable public String getCreatedAt() {
        return createdAt;
    }

    @Nullable public String getEditedAt() {
        return editedAt;
    }

    @Nullable public String getDeletedAt() {
        return deletedAt;
    }

    @NonNull public List<MessageMention> getMentions() {
        return mentions == null ? List.of() : mentions;
    }

    public boolean isMentionAll() { return mentionAll; }
    public boolean getMentionAll() { return mentionAll; }

    /** {@code "E2EE_V1"} on encrypted rows; null for plaintext. */
    @Nullable public String getEncryption() {
        return encryption;
    }

    @NonNull public List<E2eeEnvelopeDto> getE2eeEnvelopes() {
        return e2eeEnvelopes == null ? List.of() : e2eeEnvelopes;
    }


    @Nullable
    public String getResolvedSenderId() {
        if (senderId != null) {
            return senderId;
        }
        return sender == null ? null : sender.getId();
    }

    @Nullable
    public String getResolvedSenderName() {
        if (senderDisplayName != null) {
            return senderDisplayName;
        }
        return sender == null ? null : sender.getDisplayName();
    }

    @Nullable
    public String getResolvedReplyToId() {
        if (replyTo != null && replyTo.getId() != null) {
            return replyTo.getId();
        }
        return replyToId;
    }

    @NonNull
    public String getResolvedType() {
        return type != null ? type : TYPE_TEXT;
    }

    private MessageDto copyVersion(MessageDto copy) {
        copy.changeVersion = changeVersion;
        copy.generation = generation;
        return copy;
    }

    public MessageDto withContent(String content) {
        return copyVersion(new MessageDto(id, seq, conversationId, senderId, senderDisplayName, sender,
                clientMessageId, type, content, replyTo, replyToId, reactions, attachments,
                createdAt, editedAt, deletedAt, mentions, mentionAll));
    }

    public MessageDto withContent(String content, String editedAt) {
        return copyVersion(new MessageDto(id, seq, conversationId, senderId, senderDisplayName, sender,
                clientMessageId, type, content, replyTo, replyToId, reactions, attachments,
                createdAt, editedAt, deletedAt, mentions, mentionAll));
    }

    public MessageDto withReactions(java.util.List<ReactionSummaryDto> reactions) {
        return copyVersion(new MessageDto(id, seq, conversationId, senderId, senderDisplayName, sender,
                clientMessageId, type, content, replyTo, replyToId, reactions, attachments,
                createdAt, editedAt, deletedAt, mentions, mentionAll));
    }

    public MessageDto withMentionMetadata(List<MessageMention> nextMentions, boolean nextMentionAll) {
        return copyVersion(new MessageDto(id, seq, conversationId, senderId, senderDisplayName, sender,
                clientMessageId, type, content, replyTo, replyToId, reactions, attachments,
                createdAt, editedAt, deletedAt, nextMentions, nextMentionAll));
    }

    public MessageDto withDeletedAt(String deletedAt, java.util.List<ReactionSummaryDto> reactions) {
        return copyVersion(new MessageDto(id, seq, conversationId, senderId, senderDisplayName, sender,
                clientMessageId, type, content, replyTo, replyToId, reactions, attachments,
                createdAt, editedAt, deletedAt, List.of(), false));
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof MessageDto that)) {
            return false;
        }
        return Objects.equals(id, that.id)
                && Objects.equals(seq, that.seq)
                && Objects.equals(conversationId, that.conversationId)
                && Objects.equals(senderId, that.senderId)
                && Objects.equals(senderDisplayName, that.senderDisplayName)
                && Objects.equals(sender, that.sender)
                && Objects.equals(clientMessageId, that.clientMessageId)
                && Objects.equals(type, that.type)
                && Objects.equals(content, that.content)
                && Objects.equals(replyTo, that.replyTo)
                && Objects.equals(replyToId, that.replyToId)
                && Objects.equals(reactions, that.reactions)
                && Objects.equals(attachments, that.attachments)
                && Objects.equals(createdAt, that.createdAt)
                && Objects.equals(editedAt, that.editedAt)
                && Objects.equals(deletedAt, that.deletedAt)
                && Objects.equals(mentions, that.mentions)
                && mentionAll == that.mentionAll
                && Objects.equals(changeVersion, that.changeVersion)
                && Objects.equals(generation, that.generation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, seq, conversationId, senderId, senderDisplayName, sender,
                clientMessageId, type, content, replyTo, replyToId, reactions, attachments,
                createdAt, editedAt, deletedAt, mentions, mentionAll, changeVersion, generation);
    }
}
