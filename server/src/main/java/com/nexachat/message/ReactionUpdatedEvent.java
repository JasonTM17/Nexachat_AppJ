package com.nexachat.message;

import com.nexachat.message.dto.ReactionUpdatedPayload;

import java.util.UUID;

/**
 * Persist-before-broadcast signal for the reaction toggle (plan §11.3.1):
 * published inside the toggle transaction, consumed by
 * {@link InteractionBroadcastListener} AFTER COMMIT so a
 * {@code REACTION_UPDATED} frame only ever describes durable state.
 */
public record ReactionUpdatedEvent(UUID conversationId, ReactionUpdatedPayload payload) {
}
