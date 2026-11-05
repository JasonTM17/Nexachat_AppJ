package com.nexachat.summary;

/** Pure threshold rule for the long-unread conversation banner. */
public final class UnreadBannerRule {
    public static final int MIN_UNREAD_COUNT = 20;

    private UnreadBannerRule() { }

    /**
     * A non-null marker means this unread window was dismissed; the caller
     * clears it when a newer unread window should be shown again.
     */
    public static boolean shouldShow(int unreadCount, Long dismissedForSeq) {
        return unreadCount >= MIN_UNREAD_COUNT && dismissedForSeq == null;
    }
}
