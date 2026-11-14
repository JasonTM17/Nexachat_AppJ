package com.nexachat.feature.auth;

import androidx.annotation.NonNull;

import java.util.Locale;

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

    public static boolean isEmail(@NonNull String input) {
        return input.length() <= 254 && input.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+");
    }

    /** Sign-in accepts an email (lower-cased) or a phone number (E.164 punctuation stripped). */
    @NonNull public static String normalizeLoginIdentifier(@NonNull String input) {
        String value = input.trim();
        return value.contains("@") ? value.toLowerCase(Locale.ROOT) : normalizeE164(value);
    }

    public static boolean isLoginIdentifier(@NonNull String normalized) {
        return normalized.contains("@") ? isEmail(normalized) : isE164(normalized);
    }

    public static boolean passwordsMatch(@NonNull String password, @NonNull String confirmation) {
        return password.equals(confirmation);
    }
}
