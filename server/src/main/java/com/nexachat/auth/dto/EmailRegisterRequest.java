package com.nexachat.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /auth/register/email body: the address, the code mailed to it, and the
 * new account's password and display name. {@code password} is capped at 72
 * characters because bcrypt silently truncates beyond that.
 */
public record EmailRegisterRequest(
        @NotBlank @Size(max = 254) String email,
        @NotBlank @Size(max = 12) String code,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 60) String displayName
) {
}
