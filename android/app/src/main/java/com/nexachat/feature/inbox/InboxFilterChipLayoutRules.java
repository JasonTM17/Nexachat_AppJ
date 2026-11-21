package com.nexachat.feature.inbox;

/** Pure layout rules for the inbox filter chip row. */
public final class InboxFilterChipLayoutRules {

    /** Large-text tier shared with the Active Now rail's caption breakpoints. */
    private static final float WRAP_FONT_SCALE = 1.3f;

    /** Below this width the four filters cannot share one row without clipping Archived. */
    private static final int WRAP_WIDTH_DP = 360;

    private InboxFilterChipLayoutRules() {}

    /**
     * At large font scales the horizontal carousel is unreliable: clipped chips are
     * hard to discover and diagonal drags collapse the app bar instead of scrolling.
     * Wrapping the chips onto stacked rows keeps every filter reachable.
     */
    public static boolean chipsWrapOntoMultipleRows(float fontScale) {
        return chipsWrapOntoMultipleRows(fontScale, Integer.MAX_VALUE);
    }

    /**
     * Same wrap rule, plus a narrow-width trigger so Archived stays on-screen
     * on compact phones instead of sitting past the fade edge.
     */
    public static boolean chipsWrapOntoMultipleRows(float fontScale, int widthDp) {
        return fontScale >= WRAP_FONT_SCALE || widthDp < WRAP_WIDTH_DP;
    }
}
