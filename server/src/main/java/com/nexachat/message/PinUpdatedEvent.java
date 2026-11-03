package com.nexachat.message;

import com.nexachat.message.dto.PinUpdatedPayload;

import java.util.UUID;

/**
 * Persist-before-broadcast signal for pin/unpin (plan §11.3.1): published
 * inside the mutation transaction, consumed by
 * {@link InteractionBroadcastListener} AFTER COMMIT so a {@code PIN_UPDATED}
 * frame only ever describes committed pins.
 */
public record PinUpdatedEvent(UUID conversationId, PinUpdatedPayload payload) {
}
