package com.nexachat.message;

import java.util.UUID;

/**
 * Delivery-watermark advance signal — broadcast AFTER COMMIT as
 * {@code MESSAGE_DELIVERED {userId, upToSeq}} on the conversation topic so
 * senders render 2 gray ticks ("Đã nhận" / DELIVERED).
 */
public record MessageDeliveredEvent(UUID conversationId, UUID userId, long upToSeq) {
}
