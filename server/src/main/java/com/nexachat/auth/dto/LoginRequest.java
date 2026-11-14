package com.nexachat.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

/**
 * POST /auth/login body. Sign in with a phone number or a verified email: send either the legacy
 * {@code phone} field or {@code identifier} (an email address, or a phone number).
 */
public record LoginRequest(
        String phone,
        String identifier,
        @NotBlank String password
) {
    public LoginRequest(String phone, String password) {
        this(phone, null, password);
    }

    /** The value to resolve: {@code identifier} wins over the legacy {@code phone}. */
    @JsonIgnore
    public String loginIdentifier() {
        if (identifier != null && !identifier.isBlank()) {
            return identifier.strip();
        }
        return phone == null ? "" : phone.strip();
    }

    @JsonIgnore
    @AssertTrue(message = "phone or identifier is required")
    public boolean isIdentified() {
        return !loginIdentifier().isEmpty();
    }
}