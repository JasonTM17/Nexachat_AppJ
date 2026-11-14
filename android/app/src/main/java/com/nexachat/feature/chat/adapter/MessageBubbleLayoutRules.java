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

    /**
     * Start and end guideline percents for a measured row, or {@code null} before the row is
     * measured: the layout's own defaults must stay in place until a real width exists,
     * otherwise an unmeasured row would let its bubble span the whole screen.
     */
    public static float[] guidesOrNull(int innerWidthPx, float fontScale, float density) {
        int max = maxBubbleWidthPx(innerWidthPx, fontScale, density);
        if (max <= 0) {
            return null;
        }
        return new float[] {startGuidePercent(max, innerWidthPx), endGuidePercent(max, innerWidthPx)};
    }

    /** Re-applying an equal percent would only request another layout pass. */
    public static boolean differs(float current, float wanted) {
        return Math.abs(current - wanted) > 0.0005f;
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
