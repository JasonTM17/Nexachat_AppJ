package com.nexachat.feature.feed;

/** Pure decisions for the one-time "public feed is not end-to-end encrypted" notice. */
public final class FeedNoticeRules {

    private FeedNoticeRules() { }

    /**
     * Whether the screen should put the notice up now: it was never acknowledged, no copy is
     * attached already (a rotation restores one) and the fragment state can still take a commit.
     */
    public static boolean shouldShow(boolean acknowledged, boolean alreadyAttached, boolean stateSaved) {
        return !acknowledged && !alreadyAttached && !stateSaved;
    }
}
