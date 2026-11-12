package com.nexachat.feature.chat.adapter;

/**
 * Pure bubble width rules. Normal type uses min(280dp, 80% of the inner width).
 * Font scale at or above 1.5 uses min(480dp, inner width minus 32dp). A non-positive
 * measurement returns zero so the caller can wait for layout.
 */
public final class MessageBubbleLayoutRules {

    public static final float NORMAL_MAX_DP = 280f;
    public static final float LARGE_TYPE_MAX_DP = 480f;
    public static final float LARGE_TYPE_FONT_SCALE = 1.5f;
    public static final float NORMAL_WIDTH_FRACTION = 0.80f;
    public static final float LARGE_TYPE_SIDE_INSET_DP = 32f;

    private MessageBubbleLayoutRules() {
    }

    public static int maxBubbleWidthPx(int innerWidthPx, float fontScale, float density) {
        if (innerWidthPx <= 0 || density <= 0f) {
            return 0;
        }
        float capPx;
        if (fontScale >= LARGE_TYPE_FONT_SCALE) {
            capPx = Math.min(
                    LARGE_TYPE_MAX_DP * density,
                    innerWidthPx - LARGE_TYPE_SIDE_INSET_DP * density);
        } else {
            capPx = Math.min(NORMAL_MAX_DP * density, innerWidthPx * NORMAL_WIDTH_FRACTION);
        }
        if (capPx <= 0f) {
            return 0;
        }
        return Math.round(capPx);
    }

    /** Start guideline percent: {@code 1 - max / innerWidth}. */
    public static float startGuidePercent(int maxBubblePx, int innerWidthPx) {
        if (innerWidthPx <= 0 || maxBubblePx <= 0) {
            return 0f;
        }
        float ratio = Math.min(1f, maxBubblePx / (float) innerWidthPx);
        return Math.max(0f, 1f - ratio);
    }

    /** End guideline percent: {@code max / innerWidth}. */
    public static float endGuidePercent(int maxBubblePx, int innerWidthPx) {
        if (innerWidthPx <= 0 || maxBubblePx <= 0) {
            return 1f;
        }
        return Math.min(1f, maxBubblePx / (float) innerWidthPx);
    }
}
