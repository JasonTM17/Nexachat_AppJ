package com.nexachat.core.common;

import androidx.annotation.Nullable;

/**
 * A selectable per-app language. {@link #getTag()} is the BCP-47 value sent to
 * the platform; an empty tag means follow the system.
 */
public enum LanguageOption {
    SYSTEM(""),
    VIETNAMESE("vi"),
    ENGLISH("en");

    private final String tag;

    LanguageOption(String tag) {
        this.tag = tag;
    }

    public String getTag() {
        return tag;
    }

    /** Maps a stored or system tag back to an option; unknown or blank becomes {@link #SYSTEM}. */
    public static LanguageOption fromTag(@Nullable String tag) {
        if (tag == null) {
            return SYSTEM;
        }
        for (LanguageOption option : values()) {
            if (option.tag.equals(tag)) {
                return option;
            }
        }
        return SYSTEM;
    }
}
