package com.nexachat.scheduled.dto;

import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Edit payload for
 * {@code PATCH /api/v1/conversations/{id}/scheduled-messages/{scheduledId}}.
 *
 * <p>Both fields are optional; the request must carry at least one of them
 * (a request with neither is {@code VALIDATION_FAILED}). {@code sendAt} is
 * clamped server-side to {@code [now()+60s, now()+365d]} and the response
 * echoes the stored value, matching the create contract.
 */
public record UpdateScheduledMessageRequest(
        @Size(max = 10_000) String body,
        Instant sendAt
) {
}
