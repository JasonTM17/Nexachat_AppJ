package com.nexachat.message.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Canonical message wire shape (plan §10.3) — returned by the REST send/read
 * endpoints and carried inside the {@code MESSAGE_CREATED} WS envelope, so
 * client parsing is single-format.
 *
 * <p>{@code replyTo} is the embedded quote preview ({@link ReplyRefResponse})
 * for {@code replyToId}; {@code reactions} is the per-emoji summary,
 * viewer-relative via {@link ReactionView#byMe()}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageResponse(
        UUID id,
        UUID conversationId,
        Long seq,
        UUID senderId,
        String senderDisplayName,
        String type,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        String content,
        UUID clientMessageId,
        UUID replyToId,
        ReplyRefResponse replyTo,
        List<AttachmentResponse> attachments,
        List<ReactionView> reactions,
        Instant createdAt,
        Instant editedAt,
        Instant deletedAt,
        List<MessageMentionResponse> mentions,
        boolean mentionAll,
        Long changeVersion,
        Long generation
) {

    public MessageResponse(UUID id, UUID conversationId, Long seq, UUID senderId,
                           String senderDisplayName, String type, String content,
                           UUID clientMessageId, UUID replyToId, ReplyRefResponse replyTo,
                           List<AttachmentResponse> attachments, List<ReactionView> reactions,
                           Instant createdAt, Instant editedAt, Instant deletedAt) {
        this(id, conversationId, seq, senderId, senderDisplayName, type, content,
                clientMessageId, replyToId, replyTo, attachments, reactions,
                createdAt, editedAt, deletedAt, List.of(), false, null, null);
    }

    public MessageResponse(UUID id, UUID conversationId, Long seq, UUID senderId,
                           String senderDisplayName, String type, String content,
                           UUID clientMessageId, UUID replyToId, ReplyRefResponse replyTo,
                           List<AttachmentResponse> attachments, List<ReactionView> reactions,
                           Instant createdAt, Instant editedAt, Instant deletedAt,
                           List<MessageMentionResponse> mentions, boolean mentionAll) {
        this(id, conversationId, seq, senderId, senderDisplayName, type, content,
                clientMessageId, replyToId, replyTo, attachments, reactions,
                createdAt, editedAt, deletedAt, mentions, mentionAll, null, null);
    }

    /** The same message without its quote preview, for a recipient not admitted to the quoted message. */
    public MessageResponse withoutReplyPreview() {
        return new MessageResponse(id, conversationId, seq, senderId, senderDisplayName, type, content,
                clientMessageId, replyToId, null, attachments, reactions, createdAt, editedAt, deletedAt,
                mentions, mentionAll, changeVersion, generation);
    }
}
