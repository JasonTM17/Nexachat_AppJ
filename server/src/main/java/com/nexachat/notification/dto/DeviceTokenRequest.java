package com.nexachat.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeviceTokenRequest(
        @NotBlank(message = "token is required")
        @Size(max = 512, message = "token must not exceed 512 characters")
        String token,

        String platform,

        @Size(max = 100, message = "deviceLabel must not exceed 100 characters")
        String deviceLabel
) {
}
