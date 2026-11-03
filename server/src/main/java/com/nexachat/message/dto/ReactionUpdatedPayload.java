package com.nexachat.message.dto;

import java.util.List;
import java.util.UUID;

/**
 * Payload of a {@code REACTION_UPDATED} envelope — the message's complete
 * post-toggle reaction state ({@code {messageId, reactions:[{emoji, count,
 * userIds}]}}). Clients replace, not merge, their cached state for this
 * message, which keeps toggles idempotent over out-of-order frames.
 */
public record ReactionUpdatedPayload(UUID messageId, List<ReactionSlice> reactions) {
}
