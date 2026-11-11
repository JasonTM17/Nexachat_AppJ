package com.nexachat.recovery;

public record ConfirmPasswordReset(String phone, String code, String newPassword) {
}
