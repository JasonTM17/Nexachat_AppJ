package com.nexachat.data;

import androidx.annotation.Nullable;

/** App-wide appearance. SYSTEM follows the device; LIGHT/DARK pin one scheme. */
public enum ThemeMode {
    SYSTEM,
    LIGHT,
    DARK;

    /** Unknown/absent stored value → {@link #SYSTEM}. */
    public static ThemeMode fromName(@Nullable String value) {
        if (value == null) {
            return SYSTEM;
        }
        for (ThemeMode mode : values()) {
            if (mode.name().equals(value)) {
                return mode;
            }
        }
        return SYSTEM;
    }
}
