package com.nexachat.feature.label;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Arrays;

/** The eight server-supported label colors, chosen to keep white text readable. */
public enum LabelColor {
    INDIGO(0xFF3730A3),
    BLUE(0xFF1D4ED8),
    CYAN(0xFF0E7490),
    TEAL(0xFF0F766E),
    EMERALD(0xFF047857),
    AMBER(0xFFB45309),
    ROSE(0xFFBE123C),
    PURPLE(0xFF6B21A8);

    private final int backgroundColor;

    LabelColor(int backgroundColor) {
        this.backgroundColor = backgroundColor;
    }

    @NonNull public String getId() { return name(); }
    public int getBackgroundColor() { return backgroundColor; }

    @NonNull
    public static LabelColor fromId(@Nullable String id) {
        if (id != null) {
            for (LabelColor color : values()) {
                if (color.name().equalsIgnoreCase(id)) return color;
            }
        }
        return INDIGO;
    }

    @NonNull
    public static String[] ids() {
        return Arrays.stream(values()).map(LabelColor::getId).toArray(String[]::new);
    }
}
