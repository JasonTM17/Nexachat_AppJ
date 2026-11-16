package com.nexachat.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * User-selectable outgoing bubble colour. Device-level like {@link ThemeMode}:
 * stored under its stable {@link #name()} id, plum when nothing is stored.
 * Each entry carries a day and a night variant; every variant keeps the white
 * outgoing text at a contrast ratio of at least 4.5:1.
 */
public enum ChatColor {
    PLUM(0xFF733F68, 0xFF67355D),
    INDIGO(0xFF3949AB, 0xFF303F9F),
    BLUE(0xFF1565C0, 0xFF0D47A1),
    TEAL(0xFF00796B, 0xFF00695C),
    GREEN(0xFF2E7D32, 0xFF1B5E20),
    AMBER(0xFFBF360C, 0xFFA5360B),
    ROSE(0xFFC2185B, 0xFF880E4F),
    SLATE(0xFF546E7A, 0xFF455A64);

    private final int dayArgb;
    private final int nightArgb;

    ChatColor(int dayArgb, int nightArgb) {
        this.dayArgb = dayArgb;
        this.nightArgb = nightArgb;
    }

    /** Outgoing bubble fill for the light scheme. */
    public int getDayArgb() {
        return dayArgb;
    }

    /** Outgoing bubble fill for the dark scheme. */
    public int getNightArgb() {
        return nightArgb;
    }

    /** Bubble fill for the scheme the device currently renders. */
    public int bubbleArgb(boolean night) {
        return night ? nightArgb : dayArgb;
    }

    /** Unknown/absent stored value → {@link #PLUM}, the shipped default. */
    @NonNull
    public static ChatColor fromName(@Nullable String value) {
        if (value != null) {
            for (ChatColor color : values()) {
                if (color.name().equals(value)) {
                    return color;
                }
            }
        }
        return PLUM;
    }
}
