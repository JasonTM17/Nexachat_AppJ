package com.nexachat.feature.contacts;

import androidx.annotation.NonNull;
import com.nexachat.feature.profile.RecoveryEmailAddressValidator;
import java.util.Locale;
import java.util.Objects;

/** Classifies add-contact sheet input as a phone lookup or an email lookup. */
public final class AddContactInputDetector {

    public enum Kind {
        PHONE,
        EMAIL,
        INVALID
    }

    private AddContactInputDetector() {
    }

  public static boolean containsEmailHint(@NonNull String raw) {
        Objects.requireNonNull(raw, "raw");
        return raw.indexOf('@') >= 0;
    }

    @NonNull
    public static Kind classify(@NonNull String raw) {
        Objects.requireNonNull(raw, "raw");
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return Kind.INVALID;
        }
        if (containsEmailHint(trimmed)) {
            return RecoveryEmailAddressValidator.isValid(trimmed) ? Kind.EMAIL : Kind.INVALID;
        }
        return Kind.PHONE;
    }

    @NonNull
    public static String normalizeEmail(@NonNull String raw) {
        Objects.requireNonNull(raw, "raw");
        return raw.trim().toLowerCase(Locale.ROOT);
    }
}
