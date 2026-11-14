package com.nexachat.message.dto;

import com.nexachat.e2ee.dto.E2eeEnvelopeRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

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
        @Size(max = 10_000) String content,
        UUID replyToId,
        List<UUID> attachmentIds,
        @Valid @Size(max = 20) List<MessageMentionRequest> mentions,
        Boolean mentionAll,
        String encryption,
        @Valid @Size(max = 20) List<E2eeEnvelopeRequest> e2eeEnvelopes
) {

    public SendMessageRequest(UUID clientMessageId, String type, String content,
                              UUID replyToId, List<UUID> attachmentIds) {
        this(clientMessageId, type, content, replyToId, attachmentIds, null, null, null, null);
    }

    public SendMessageRequest(UUID clientMessageId, String type, String content,
                              UUID replyToId, List<UUID> attachmentIds,
                              List<MessageMentionRequest> mentions, Boolean mentionAll) {
        this(clientMessageId, type, content, replyToId, attachmentIds, mentions, mentionAll, null, null);
    }
}
