package com.nexachat.scheduled.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * Schedule payload for {@code POST /api/v1/conversations/{id}/scheduled-messages}.
 *
 * <p>{@code clientMessageId} is the idempotency key (client-generated UUID)
 * — it is also the {@code clientMessageId} the dispatched message carries, so
 * a replayed POST and a retried send resolve to the same row either way.
 * {@code sendAt} is clamped server-side to {@code [now()+60s, now()+365d]};
 * the response echoes the clamped value.
 */
public record CreateScheduledMessageRequest(
        @NotNull UUID clientMessageId,
        @NotNull @Size(max = 10_000) String body,
        @NotNull Instant sendAt
) {
}
