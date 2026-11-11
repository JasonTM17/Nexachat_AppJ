package com.nexachat.user.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * DELETE /users/me body — current password is required to confirm deletion.
 */
public record DeleteAccountRequest(@NotBlank String password) {
}
