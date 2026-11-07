package com.nexachat.feature.auth;

import androidx.annotation.NonNull;

/** Small, UI-independent helpers shared by the Java authentication forms. */
public final class AuthFieldValidators {
    private AuthFieldValidators() {}

    /** Normalizes common phone punctuation and applies the server's E.164 shape. */
    @NonNull public static String normalizeE164(@NonNull String input) {
        String value = input.trim().replaceAll("[\\s().-]", "");
        if (value.startsWith("00")) value = "+" + value.substring(2);
        return value;
    }

    public static boolean isE164(@NonNull String input) {
        return input.matches("\\+[1-9][0-9]{7,14}");
    }

    public static boolean passwordsMatch(@NonNull String password, @NonNull String confirmation) {
        return password.equals(confirmation);
    }
}
