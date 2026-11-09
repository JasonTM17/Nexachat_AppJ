package com.nexachat.auth.dto;

import com.nexachat.user.dto.UserResponse;

/**
 * Token payload returned by register/login/refresh (plan §10.3), including
 * additive client policy fields for the effective recall window and group-admin
 * moderation after that window.
 */
public record AuthResponse(
        UserResponse user,
        String accessToken,
        long accessTokenExpiresIn,
        String refreshToken,
        long recallWindowSeconds,
        boolean groupAdminMayRecallAfterWindow
) {
}
