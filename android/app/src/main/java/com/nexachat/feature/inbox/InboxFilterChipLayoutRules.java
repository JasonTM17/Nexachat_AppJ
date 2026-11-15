package com.nexachat.feature.inbox;

/** Pure layout rules for the inbox filter chip row. */
public final class InboxFilterChipLayoutRules {

    /** Large-text tier shared with the Active Now rail's caption breakpoints. */
    private static final float WRAP_FONT_SCALE = 1.3f;

    private InboxFilterChipLayoutRules() {}

    /**
     * At large font scales the horizontal carousel is unreliable: clipped chips are
     * hard to discover and diagonal drags collapse the app bar instead of scrolling.
     * Wrapping the chips onto stacked rows keeps every filter reachable.
     */
    public static boolean chipsWrapOntoMultipleRows(float fontScale) {
        return fontScale >= WRAP_FONT_SCALE;
    }
}
