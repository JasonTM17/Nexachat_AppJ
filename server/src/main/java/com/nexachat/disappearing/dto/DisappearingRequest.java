package com.nexachat.disappearing.dto;

import jakarta.validation.constraints.NotNull;

/**
 * {@code PUT /conversations/{id}/disappearing} body. {@code seconds} is the
 * per-conversation disappearing-message timer; {@code 0} clears it. Only the
 * allow-listed durations are valid — the service rejects anything else.
 */
public record DisappearingRequest(@NotNull Integer seconds) {
}
