package com.nexachat.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RecoveryEmailRequest(
        @NotBlank @Size(max = 254) String email) {
}
