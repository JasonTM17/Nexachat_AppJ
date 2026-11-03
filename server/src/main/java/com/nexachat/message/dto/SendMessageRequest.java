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
 * {@code attachmentIds} references unbound uploads the caller created via
 * {@code POST /api/v1/attachments} — each id must exist, be owned by the
 * sender, and still be unbound; the send transaction claims them (phase-09,
 * max 10).
 * TEXT takes an empty/absent list; media types require at least one id whose
 * sniffed MIME matches the declared type.
 */
public record SendMessageRequest(
        @NotNull UUID clientMessageId,
        @NotNull String type,
        String content,
        UUID replyToId,
        List<UUID> attachmentIds
) {
}
