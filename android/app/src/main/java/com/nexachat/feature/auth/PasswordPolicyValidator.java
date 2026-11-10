package com.nexachat.feature.auth;

import androidx.annotation.NonNull;
import java.nio.charset.StandardCharsets;

/** Client-side checks aligned with server {@code PasswordPolicy} and registration UI. */
public final class PasswordPolicyValidator {

    public static final int MIN_LENGTH = AuthViewModel.MIN_PASSWORD_LENGTH;
    public static final int MAX_UTF8_BYTES = 72;

    private PasswordPolicyValidator() {
    }

    public static boolean isAcceptable(@NonNull String password) {
        if (password.indexOf('\0') >= 0) {
            return false;
        }
        if (password.length() < MIN_LENGTH) {
            return false;
        }
        return password.getBytes(StandardCharsets.UTF_8).length <= MAX_UTF8_BYTES;
    }

    public static boolean isTooShort(@NonNull String password) {
        return password.length() < MIN_LENGTH;
    }
}
