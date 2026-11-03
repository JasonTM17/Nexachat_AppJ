package com.nexachat.auth.dto;

import com.nexachat.user.dto.UserResponse;

/**
 * Token payload returned by register/login/refresh (plan §10.3):
 * {@code {user:{...}, accessToken, accessTokenExpiresIn:900, refreshToken}}.
 */
public record AuthResponse(
        UserResponse user,
        String accessToken,
        long accessTokenExpiresIn,
        String refreshToken
) {
}
