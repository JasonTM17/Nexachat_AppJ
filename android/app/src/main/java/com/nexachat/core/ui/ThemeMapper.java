package com.nexachat.core.ui;

import com.nexachat.data.ThemeMode;

/**
 * Pure presentation logic for Theme mode mapping (F6, D4).
 * Written in Java per the project owner language rule.
 */
public final class ThemeMapper {

    private ThemeMapper() {}

    /**
     * Resolves whether dark theme should be rendered given the current [ThemeMode]
     * and the platform system dark theme state.
     */
    public static boolean resolveIsDarkTheme(ThemeMode themeMode, boolean isSystemInDarkTheme) {
        if (themeMode == null || themeMode == ThemeMode.SYSTEM) {
            return isSystemInDarkTheme;
        }
        return themeMode == ThemeMode.DARK;
    }

    /**
     * Resolves whether the system bars (status & navigation bar) should use dark/light icons (D4).
     * When dark theme is active, icons must be light (isAppearanceLight = false).
     * When light theme is active, icons must be dark (isAppearanceLight = true).
     */
    public static boolean resolveAppearanceLightBars(ThemeMode themeMode, boolean isSystemInDarkTheme) {
        return !resolveIsDarkTheme(themeMode, isSystemInDarkTheme);
    }

    /**
     * Maps [ThemeMode] to its localized display label.
     */
    public static String resolveThemeLabel(
            ThemeMode themeMode,
            String systemLabel,
            String lightLabel,
            String darkLabel
    ) {
        if (themeMode == null) {
            return systemLabel;
        }
        switch (themeMode) {
            case LIGHT:
                return lightLabel;
            case DARK:
                return darkLabel;
            case SYSTEM:
            default:
                return systemLabel;
        }
    }
}
