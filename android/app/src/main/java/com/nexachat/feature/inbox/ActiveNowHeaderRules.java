package com.nexachat.feature.inbox;

/** Pure rules for the inbox Active Now section header. */
public final class ActiveNowHeaderRules {

    private ActiveNowHeaderRules() {}

    /**
     * True when the title and the trailing action cannot share the row while
     * keeping a visible separation; the action then stacks under the title.
     */
    public static boolean actionStacksBelowTitle(
            float titleWidthPx,
            float actionWidthPx,
            float separationPx,
            float availableWidthPx) {
        return titleWidthPx + separationPx + actionWidthPx > availableWidthPx;
    }
}
