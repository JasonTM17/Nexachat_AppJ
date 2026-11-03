package com.nexachat.contact.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /contacts body. {@code phone} accepts local or international format —
 * strict E.164 validation happens in {@code PhoneService.toE164}, same rule as
 * register (do not reject local formats with a regex here).
 */
public record AddContactRequest(
        @NotBlank String phone
) {
}
