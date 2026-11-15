package com.nexachat.message.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payload of a {@code REACTION_UPDATED} envelope — the message's complete
 * post-toggle reaction state ({@code {messageId, reactions:[{emoji, count,
 * userIds}], messageCreatedAt}}). Clients replace, not merge, their cached
 * state for this message, which keeps toggles idempotent over out-of-order
 * frames. {@code messageCreatedAt} is the named message's creation instant;
 * the outbound membership gate uses it to deliver the frame only to members
 * admitted at that time, and it is omitted when unset.
 */
public record ReactionUpdatedPayload(UUID messageId, List<ReactionSlice> reactions,
                                     @JsonInclude(JsonInclude.Include.NON_NULL)
                                     Instant messageCreatedAt) {

    public ReactionUpdatedPayload(UUID messageId, List<ReactionSlice> reactions) {
        this(messageId, reactions, null);
    }
}
