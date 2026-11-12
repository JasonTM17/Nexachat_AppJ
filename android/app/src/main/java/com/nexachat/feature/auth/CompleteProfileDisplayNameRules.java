package com.nexachat.feature.auth;

import androidx.annotation.NonNull;
import com.nexachat.core.common.WhitespaceTrim;

/** Display-name rules for the one-time Google signup completion screen. */
public final class CompleteProfileDisplayNameRules {

    public static final int MAX_LENGTH = 60;

    private CompleteProfileDisplayNameRules() {}

    public static boolean isValid(@NonNull String raw) {
        String trimmed = WhitespaceTrim.trim(raw);
        return !trimmed.isEmpty() && trimmed.length() <= MAX_LENGTH;
    }

    @NonNull
    public static String normalize(@NonNull String raw) {
        return WhitespaceTrim.trim(raw);
    }
}
