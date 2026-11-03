package com.nexachat.message.dto;

import com.nexachat.message.Message;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-side projection for message lists and post-insert read-back (plan
 * §9.3.7 — direct projection query, no entity graph). Carries the sender's
 * {@code displayName} so {@code MessageResponse} assembly never lazy-loads.
 */
public record MessageRow(
        UUID id,
        UUID conversationId,
        Long seq,
        UUID senderId,
        String senderDisplayName,
        Message.Type type,
        String content,
        UUID clientMessageId,
        UUID replyToId,
        Instant createdAt,
        Instant editedAt
) {
}
