package com.nexachat.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * PUT /users/me/phone body: local or international format, normalized to E.164 server-side.
 * {@code password} is required only to replace a phone that is already linked.
 */
public record LinkPhoneRequest(@NotBlank @Size(max = 32) String phone,
                               @Size(max = 128) String password) {

    public LinkPhoneRequest(String phone) {
        this(phone, null);
    }
}
