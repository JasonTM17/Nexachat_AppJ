package com.nexachat.feature.inbox;

import androidx.annotation.Nullable;
import java.util.Locale;

/** Recognizes inbox preview markers without changing message content. */
public enum InboxPreviewKind {
    TEXT,
    PHOTO,
    VOICE,
    FILE,
    CALL;

    public static InboxPreviewKind fromContent(@Nullable String content) {
        if (content == null || content.isBlank()) {
            return TEXT;
        }
        String value = content.trim().toUpperCase(Locale.ROOT);
        if (value.startsWith("CALL|")) {
            return CALL;
        }
        if (isMarker(value, "IMAGE", "PHOTO", "VIDEO")) {
            return PHOTO;
        }
        if (isMarker(value, "AUDIO", "VOICE")) {
            return VOICE;
        }
        if (isMarker(value, "FILE", "ATTACHMENT")) {
            return FILE;
        }
        return TEXT;
    }

    private static boolean isMarker(String value, String... markers) {
        for (String marker : markers) {
            if (value.equals(marker) || value.startsWith(marker + "|")) {
                return true;
            }
        }
        return false;
    }
}
