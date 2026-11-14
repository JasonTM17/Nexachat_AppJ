package com.nexachat.e2ee.dto;

import java.util.UUID;

/** One active member device inside {@code GET /conversations/{id}/e2ee}. */
public record E2eeDeviceView(UUID userId, UUID deviceId, String publicKey) {
}
