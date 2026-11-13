package com.nexachat.core.ui.components;

import androidx.annotation.Nullable;

/** Geometry and visibility rules for the presence dot on {@link AvatarView}. */
public final class AvatarPresenceDotRules {

    static final float DOT_SCALE = 0.28f;
    static final int MIN_DOT_DP = 10;
    static final int MAX_DOT_DP = 16;
    static final int STROKE_DP = 2;

    /** Inset from the square corner toward the circle's 45° point (fraction of dot diameter). */
    private static final float CORNER_INSET_FRACTION = 0.22f;

    private AvatarPresenceDotRules() {}

    public static boolean shouldShowDot(@Nullable Boolean onlineStatus) {
        return Boolean.TRUE.equals(onlineStatus);
    }

    public static int dotDiameterDp(int avatarDiameterDp) {
        int scaled = Math.round(avatarDiameterDp * DOT_SCALE);
        return Math.min(MAX_DOT_DP, Math.max(MIN_DOT_DP, scaled));
    }

  /** @return margin toward the avatar center along the bottom-end corner, in dp */
    public static int dotCornerInsetDp(int dotDiameterDp) {
        return Math.max(0, Math.round(dotDiameterDp * CORNER_INSET_FRACTION));
    }

    public static int dotDiameterPx(int avatarDiameterPx, float density) {
        int avatarDp = Math.round(avatarDiameterPx / density);
        return Math.round(dotDiameterDp(avatarDp) * density);
    }

    public static int dotCornerInsetPx(int dotDiameterPx, float density) {
        int dotDp = Math.round(dotDiameterPx / density);
        return Math.round(dotCornerInsetDp(dotDp) * density);
    }
}
