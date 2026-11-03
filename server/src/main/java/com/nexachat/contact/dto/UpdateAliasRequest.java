package com.nexachat.contact.dto;

import jakarta.validation.constraints.Size;

/**
 * PATCH /contacts/{userId} body. {@code aliasName} is nullable: JSON
 * {@code null} (or an absent field) clears the alias; a present value is
 * trimmed and must be non-blank — enforced in the service layer, matching the
 * PATCH /users/me convention.
 */
public record UpdateAliasRequest(
        @Size(max = 60) String aliasName
) {
}
