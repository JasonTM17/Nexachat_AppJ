package com.nexachat.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /auth/register body. {@code phone} accepts local or international format —
 * strict E.164 validation happens in the normalization layer, not via regex here,
 * so local numbers like {@code 0912345678} are not rejected prematurely.
 * {@code password} is capped at 72 chars because bcrypt silently truncates beyond that.
 */
public record RegisterRequest(
        @NotBlank String phone,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 60) String displayName
) {
}
