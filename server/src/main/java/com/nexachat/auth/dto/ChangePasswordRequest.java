package com.nexachat.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * PATCH /auth/password body.
 *
 * @param refreshToken optional: when supplied, that refresh token is kept alive and
 *                     every OTHER session of the user is revoked (spec: "revoke all
 *                     other refresh tokens" — lets the current device stay signed in
 *                     while forcing other devices to re-login, FR-A6). When absent,
 *                     ALL refresh tokens are revoked.
 */
public record ChangePasswordRequest(
        @NotBlank String oldPassword,
        @NotBlank @Size(min = 8, max = 72) String newPassword,
        String refreshToken
) {
}
