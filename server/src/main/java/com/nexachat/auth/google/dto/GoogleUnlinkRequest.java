package com.nexachat.auth.google.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Unlinks the Google account after a password re-authentication.
 *
 * @param password the account's current password
 */
public record GoogleUnlinkRequest(
        @NotBlank @Size(max = 128) String password) {
}
