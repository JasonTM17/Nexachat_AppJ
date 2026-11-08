package com.nexachat.rag;

import java.time.Instant;
import java.util.UUID;

/** An eligible conversation message used as a retrieval unit. */
public record RagMessage(
        UUID id,
        UUID conversationId,
        long seq,
        UUID senderId,
        String senderName,
        String content,
        Instant createdAt,
        Instant editedAt,
        UUID replyToId) {

    public RagMessage {
        content = content == null ? "" : content;
        senderName = senderName == null ? "" : senderName;
    }
}
