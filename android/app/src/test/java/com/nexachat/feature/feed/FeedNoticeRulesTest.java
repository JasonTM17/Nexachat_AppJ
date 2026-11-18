package com.nexachat.feature.feed;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FeedNoticeRulesTest {

    @Test public void anUnacknowledgedNoticeShowsOnce() {
        assertTrue(FeedNoticeRules.shouldShow(false, false, false));
    }

    @Test public void anAcknowledgedNoticeNeverShowsAgain() {
        assertFalse(FeedNoticeRules.shouldShow(true, false, false));
    }

    @Test public void aRestoredCopyIsNotDuplicated() {
        assertFalse(FeedNoticeRules.shouldShow(false, true, false));
    }

    @Test public void aSavedStateDefersTheNotice() {
        assertFalse(FeedNoticeRules.shouldShow(false, false, true));
    }
}
