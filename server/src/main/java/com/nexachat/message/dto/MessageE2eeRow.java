package com.nexachat.message.dto;

import java.util.UUID;

/**
 * Encryption sidecar for {@link MessageRow}: the {@code encryption} scheme tag
 * and the raw JSONB envelope array, batch-fetched by message id during
 * response hydration so every read surface carries the same fields.
 */
public record MessageE2eeRow(UUID id, String encryption, String e2eeEnvelopes) {
}
