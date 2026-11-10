package com.nexachat.user.dto;

import jakarta.validation.constraints.Size;

public record VerifyEmailRequest(@Size(max = 32) String code) {
}
