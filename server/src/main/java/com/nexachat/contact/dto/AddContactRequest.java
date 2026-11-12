package com.nexachat.contact.dto;

import jakarta.validation.constraints.Size;

/**
 * POST /contacts body. Exactly one of {@code phone} or {@code email} must be
 * supplied — XOR is enforced in {@link com.nexachat.contact.ContactController}.
 * {@code phone} accepts local or international format (E.164 in
 * {@code PhoneService}); {@code email} is trimmed, lowercased, and validated
 * like recovery email ({@code EmailOtpService.normalizeEmail}).
 */
public record AddContactRequest(
        @Size(max = 32) String phone,
        @Size(max = 254) String email
) {
}
