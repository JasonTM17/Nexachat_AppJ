package com.nexachat.message.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Send payload shared by the two transports (plan §10.2 note: one
 * {@code MessageService.send()} enforces authZ + idempotency for both):
 * STOMP {@code /app/conversations/{id}/messages} and the REST fallback
 * {@code POST /conversations/{id}/messages}.
 *
 * <p>{@code clientMessageId} is the idempotency key (client-generated UUID);
 * {@code attachmentIds} reserves the phase-8 binding flow — currently every
 * {@code message_attachments} row is bound at insert time, so a non-empty list
 * can only reference already-bound rows and is rejected.
 */
public record SendMessageRequest(
        @NotNull UUID clientMessageId,
        @NotNull String type,
        String content,
        UUID replyToId,
        List<UUID> attachmentIds
) {
}
