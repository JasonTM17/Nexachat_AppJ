package com.nexachat.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** PUT /users/me/phone body: local or international format, normalized to E.164 server-side. */
public record LinkPhoneRequest(@NotBlank @Size(max = 32) String phone) {
}
