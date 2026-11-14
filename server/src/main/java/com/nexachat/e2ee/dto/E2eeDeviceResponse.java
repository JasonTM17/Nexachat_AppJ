package com.nexachat.e2ee.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/** Registered device as returned by the device PUT (base64url public key). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record E2eeDeviceResponse(UUID deviceId, String publicKey, Instant createdAt) {
}
