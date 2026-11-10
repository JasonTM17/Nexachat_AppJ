package com.nexachat.auth.google.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Links a Google account to the signed-in user. The password re-authenticates
 * the owner of the account; the nonce makes the ID token single-use.
 *
 * @param idToken  the raw JWT returned by Credential Manager
 * @param nonce    the single-use value from {@code POST /api/v1/auth/google/nonce}
 * @param password the account's current password
 */
public record GoogleLinkRequest(
        @NotBlank @Size(max = 8192) String idToken,
        @NotBlank @Size(max = 512) String nonce,
        @NotBlank @Size(max = 128) String password) {
}
