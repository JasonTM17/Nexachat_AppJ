package com.nexachat.message.dto;

import java.util.UUID;

/**
 * Payload of a {@code PIN_UPDATED} envelope: {@code {messageId, pinned}} —
 * the message's pin state inside the envelope's {@code conversationId}.
 * {@code true} after a pin, {@code false} after an unpin.
 */
public record PinUpdatedPayload(UUID messageId, boolean pinned) {
}
