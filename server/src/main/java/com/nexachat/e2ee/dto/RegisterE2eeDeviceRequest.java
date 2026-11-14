package com.nexachat.e2ee.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PUT /api/v1/e2ee/devices/{deviceId}}: the device's X25519
 * public key, base64url-no-padding encoded, exactly 32 bytes.
 */
public record RegisterE2eeDeviceRequest(@NotNull String publicKey) {
}
