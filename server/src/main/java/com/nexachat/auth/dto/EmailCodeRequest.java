package com.nexachat.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** POST /auth/register/email-code body. */
public record EmailCodeRequest(@NotBlank @Size(max = 254) String email) {
}
