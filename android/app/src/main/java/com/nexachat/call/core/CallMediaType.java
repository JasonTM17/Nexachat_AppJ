package com.nexachat.call.core;

import androidx.annotation.Nullable;
import java.util.Locale;

/**
 * Media kind negotiated for a call. An absent or unrecognized wire value means
 * AUDIO so pre-mediaType clients and push payloads keep working unchanged.
 */
public enum CallMediaType {
    AUDIO,
    VIDEO;

    public static CallMediaType parse(@Nullable String wireValue) {
        if (wireValue == null) return AUDIO;
        try {
            return valueOf(wireValue.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return AUDIO;
        }
    }

    public static CallMediaType orAudio(@Nullable CallMediaType mediaType) {
        return mediaType == null ? AUDIO : mediaType;
    }
}
