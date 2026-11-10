package com.nexachat.feature.auth;

import androidx.annotation.NonNull;
import java.util.Objects;

/** Normalizes pasted or typed OTP input to at most six digits. */
public final class PasswordResetOtpSanitizer {
    public static final int OTP_LENGTH = 6;

    private PasswordResetOtpSanitizer() {
    }

    @NonNull
    public static String sanitize(@NonNull String input) {
        Objects.requireNonNull(input, "input");
        StringBuilder digits = new StringBuilder(OTP_LENGTH);
        for (int i = 0; i < input.length() && digits.length() < OTP_LENGTH; i++) {
            char c = input.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        return digits.toString();
    }

    public static boolean isComplete(@NonNull String code) {
        return sanitize(code).length() == OTP_LENGTH;
    }
}
