package com.nexachat.core.common;

import androidx.annotation.Nullable;
import com.nexachat.core.network.dto.UserDto;
import com.nexachat.data.Contact;

/**
 * Subtitle line for a person row: verified email when present, otherwise nothing.
 * Never surfaces the literal {@code "null"}.
 */
public final class PersonContactSubtitle {

    private PersonContactSubtitle() {
    }

    @Nullable
    public static String subtitle(@Nullable UserDto user) {
        if (user == null) {
            return null;
        }
        return subtitle(user.getEmail(), user.getEmailVerified());
    }

    @Nullable
    public static String subtitle(@Nullable Contact contact) {
        if (contact == null) {
            return null;
        }
        return subtitle(contact.getEmail(), contact.getEmailVerified());
    }

    @Nullable
    public static String subtitle(@Nullable String email, @Nullable Boolean emailVerified) {
        if (email == null || isBlank(email) || "null".equalsIgnoreCase(email.trim())) {
            return null;
        }
        if (!Boolean.TRUE.equals(emailVerified)) {
            return null;
        }
        return email.trim();
    }

    private static boolean isBlank(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!Character.isWhitespace(character) && !Character.isSpaceChar(character)) {
                return false;
            }
        }
        return true;
    }
}
