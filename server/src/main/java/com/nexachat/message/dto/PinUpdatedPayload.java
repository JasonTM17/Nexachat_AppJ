package com.nexachat.message.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload of a {@code PIN_UPDATED} envelope: {@code {messageId, pinned,
 * messageCreatedAt}} — the message's pin state inside the envelope's
 * {@code conversationId}. {@code true} after a pin, {@code false} after an
 * unpin. {@code messageCreatedAt} is the named message's creation instant;
 * the outbound membership gate uses it to deliver the frame only to members
 * admitted at that time, and it is omitted when unset.
 */
public record PinUpdatedPayload(UUID messageId, boolean pinned,
                                @JsonInclude(JsonInclude.Include.NON_NULL)
                                Instant messageCreatedAt) {

    public PinUpdatedPayload(UUID messageId, boolean pinned) {
        this(messageId, pinned, null);
    }
}
