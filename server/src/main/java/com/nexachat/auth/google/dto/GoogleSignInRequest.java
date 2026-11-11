package com.nexachat.auth.google.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Sign-in with a Google ID token.
 *
 * @param idToken the raw JWT returned by Credential Manager
 * @param nonce   the single-use value from {@code POST /api/v1/auth/google/nonce}
 */
public record GoogleSignInRequest(
        @NotBlank @Size(max = 8192) String idToken,
        @NotBlank @Size(max = 512) String nonce) {
}
