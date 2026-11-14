package com.nexachat.message.dto;

import com.nexachat.message.Message;

import java.time.Instant;
import java.util.UUID;

/**
 * Projection feeding {@link ReplyRefResponse} assembly — the reply target's
 * id, sender display name, type, content, and {@code deletedAt} flag in one
 * batched IN query (no entity graph, plan §9.3.7).
 */
public record ReplyRefRow(UUID id,
                          UUID senderId,
                          String senderDisplayName,
                          Message.Type type,
                          String content,
                          Instant deletedAt,
                          Instant createdAt) {
}
