package com.nexachat.conversation.dto;

import java.time.Instant;

/**
 * Body of {@code PUT /api/v1/conversations/{id}/mute}.
 *
 * <p>Exactly one of {@code mutedUntil} (an ISO-8601 UTC instant, strictly in the
 * future and at most 365 days out) or {@code forever: true} must be supplied.
 * Supplying both, supplying neither, or passing a past instant is rejected with
 * {@code 400 VALIDATION_FAILED} — the endpoint fails closed rather than guessing
 * a duration.
 *
 * @param mutedUntil mute expiry, or {@code null} for a forever mute
 * @param forever     {@code true} to mute forever
 */
public record MuteRequest(
        Instant mutedUntil,
        Boolean forever
) {
}
