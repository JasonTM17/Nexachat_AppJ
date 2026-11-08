package com.nexachat.notification.dto;

import java.time.Instant;
import java.util.UUID;

public record DeviceTokenResponse(
        UUID id,
        String token,
        String platform,
        String deviceLabel,
        Instant updatedAt
) {
}
