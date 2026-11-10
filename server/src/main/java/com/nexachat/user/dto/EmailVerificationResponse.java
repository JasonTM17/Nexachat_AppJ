package com.nexachat.user.dto;

public record EmailVerificationResponse(String email, boolean verified) {
}
