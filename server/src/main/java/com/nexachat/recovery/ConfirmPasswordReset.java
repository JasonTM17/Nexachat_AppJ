package com.nexachat.recovery;

public record ConfirmPasswordReset(String phone, String email, String code, String newPassword) {

    public ConfirmPasswordReset(String phone, String code, String newPassword) {
        this(phone, null, code, newPassword);
    }
}
