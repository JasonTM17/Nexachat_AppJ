package com.nexachat.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank String phone,
        @NotBlank String password
) {
}
