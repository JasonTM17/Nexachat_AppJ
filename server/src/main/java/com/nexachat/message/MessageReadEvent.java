package com.nexachat.message;

import java.util.UUID;

/**
 * Watermark-advance signal — broadcast AFTER COMMIT as
 * {@code MESSAGE_READ {userId, upToSeq}} on the conversation topic so partners
 * render "Đã xem" (seen) receipts (plan §7.2 note).
 */
public record MessageReadEvent(UUID conversationId, UUID userId, long upToSeq) {
}
