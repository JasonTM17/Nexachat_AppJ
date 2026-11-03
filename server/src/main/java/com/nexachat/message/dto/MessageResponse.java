package com.nexachat.message.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Canonical message wire shape (plan §10.3) — returned by the REST send/read
 * endpoints and carried inside the {@code MESSAGE_CREATED} WS envelope, so
 * client parsing is single-format.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageResponse(
        UUID id,
        UUID conversationId,
        Long seq,
        UUID senderId,
        String senderDisplayName,
        String type,
        String content,
        UUID clientMessageId,
        UUID replyToId,
        List<AttachmentResponse> attachments,
        Instant createdAt,
        Instant editedAt
) {
}
