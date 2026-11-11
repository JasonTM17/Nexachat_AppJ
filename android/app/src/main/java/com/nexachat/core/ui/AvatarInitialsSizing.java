package com.nexachat.core.ui;

import androidx.annotation.Nullable;

/** Caps avatar initials text size in px so 1–2 glyphs fit inside the circle at any font scale. */
public final class AvatarInitialsSizing {

    private AvatarInitialsSizing() { }

    /**
     * Text size in raw pixels for {@link android.util.TypedValue#COMPLEX_UNIT_PX},
     * independent of the user's font-scale preference.
     */
    public static float textSizePx(int diameterPx, @Nullable String displayName, float density) {
        if (diameterPx <= 0 || density <= 0f) {
            return 0f;
        }
        String initials = MessageUiRules.extractInitials(displayName);
        int glyphCount = Math.max(1, initials.length());
        float sizeDp = diameterPx / density;
        float preferredPx = preferredPxFromDiameterDp(sizeDp, density);
        float capPx = diameterPx * (glyphCount > 1 ? 0.36f : 0.44f);
        return Math.min(preferredPx, capPx);
    }

    private static float preferredPxFromDiameterDp(float sizeDp, float density) {
        if (sizeDp >= 72f) return 26f * density;
        if (sizeDp >= 56f) return 20f * density;
        if (sizeDp >= 40f) return 15f * density;
        if (sizeDp >= 32f) return 12f * density;
        return 10f * density;
    }
}
