package com.nexachat.feature.auth;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.common.WhitespaceTrim;

/** Shared display-name rules for register and profile-completion flows. */
public final class CompleteProfileDisplayNameRules {

    public static final int MAX_LENGTH = 60;

    public enum Problem {
        BLANK,
        TOO_LONG
    }

    private CompleteProfileDisplayNameRules() {}

    @Nullable
    public static Problem problem(@NonNull String raw) {
        String trimmed = WhitespaceTrim.trim(raw);
        if (trimmed.isEmpty()) {
            return Problem.BLANK;
        }
        if (trimmed.length() > MAX_LENGTH) {
            return Problem.TOO_LONG;
        }
        return null;
    }

    public static boolean isValid(@NonNull String raw) {
        return problem(raw) == null;
    }

    @NonNull
    public static String normalize(@NonNull String raw) {
        return WhitespaceTrim.trim(raw);
    }
}
