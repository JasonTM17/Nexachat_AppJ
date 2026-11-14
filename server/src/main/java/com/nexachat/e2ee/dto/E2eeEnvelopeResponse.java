package com.nexachat.e2ee.dto;

import java.util.UUID;

/** Stored envelope echoed back on {@code MessageResponse#e2eeEnvelopes}. */
public record E2eeEnvelopeResponse(UUID deviceId, String wrappedKey) {
}
