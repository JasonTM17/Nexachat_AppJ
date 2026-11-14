package com.nexachat.core.ui.components;

/** Width heuristics for settings rows with a trailing value column. */
public final class SettingsRowLayoutRules {

    private static final int HORIZONTAL_PADDING_DP = 32;
    private static final int LEADING_WITH_ICON_DP = 56;
    private static final int TRAILING_VALUE_CAP_DP = 140;
    private static final int VALUE_MARGINS_DP = 12;
    private static final int CHEVRON_BLOCK_DP = 24;
    private static final int MIN_TITLE_WIDTH_DP = 80;

    private SettingsRowLayoutRules() {}

  public static boolean shouldStackTrailingValue(
      float density,
      int rowWidthPx,
      boolean valueVisible,
      boolean chevronVisible,
      boolean leadingIconVisible) {
        return shouldStackTrailingValue(
                density, rowWidthPx, valueVisible, chevronVisible, leadingIconVisible, 0f);
    }

    /**
     * Stacks the value under the title when the inline value would leave the title too narrow,
     * or narrower than its own single-line width (so the title never wraps beside a value).
     */
    public static boolean shouldStackTrailingValue(
            float density,
            int rowWidthPx,
            boolean valueVisible,
            boolean chevronVisible,
            boolean leadingIconVisible,
            float titleTextWidthPx) {
        if (!valueVisible || rowWidthPx <= 0) {
            return false;
        }
        int inner = rowWidthPx - Math.round(HORIZONTAL_PADDING_DP * density);
        int leading = leadingIconVisible ? Math.round(LEADING_WITH_ICON_DP * density) : 0;
        int trailing = Math.round((TRAILING_VALUE_CAP_DP + VALUE_MARGINS_DP) * density);
        if (chevronVisible) {
            trailing += Math.round(CHEVRON_BLOCK_DP * density);
        }
        int titleSpace = inner - leading - trailing;
        return titleSpace < Math.round(MIN_TITLE_WIDTH_DP * density) || titleTextWidthPx > titleSpace;
    }
}
