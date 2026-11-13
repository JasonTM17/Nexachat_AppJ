package com.nexachat.feature.profile;

import androidx.annotation.Nullable;

import com.nexachat.core.ui.ThemeMapper;
import com.nexachat.data.ThemeMode;

/** Pure rules for profile appearance controls. */
public final class ProfileThemeUiRules {

    private ProfileThemeUiRules() {}

    /**
     * When quick chips show the active theme, the settings row must not repeat the same label.
     */
    @Nullable
    public static String resolveThemeRowTrailingValue(
            ThemeMode mode,
            String systemLabel,
            String lightLabel,
            String darkLabel,
            boolean quickChipsVisible) {
        if (quickChipsVisible) {
            return null;
        }
        return ThemeMapper.resolveThemeLabel(mode, systemLabel, lightLabel, darkLabel);
    }
}
