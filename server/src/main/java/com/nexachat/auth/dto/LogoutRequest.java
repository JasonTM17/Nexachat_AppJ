package com.nexachat.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** POST /auth/logout body — the refresh token of the session being ended. */
public record LogoutRequest(
        @NotBlank String refreshToken
) {
}
