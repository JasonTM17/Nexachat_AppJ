package com.nexachat.e2ee.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * One per-device wrapped content key on an encrypted send: the content key
 * AES-GCM-wrapped to {@code deviceId}'s public key, base64url encoded.
 */
public record E2eeEnvelopeRequest(@NotNull UUID deviceId, @NotNull String wrappedKey) {
}
