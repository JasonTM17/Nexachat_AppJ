package com.nexachat.feature.inbox;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Wrap thresholds for the inbox filter chip row. */
public final class InboxFilterChipLayoutRulesTest {

    @Test
    public void defaultAndModerateScalesKeepTheHorizontalCarousel() {
        assertFalse(InboxFilterChipLayoutRules.chipsWrapOntoMultipleRows(1.0f));
        assertFalse(InboxFilterChipLayoutRules.chipsWrapOntoMultipleRows(1.29f));
    }

    @Test
    public void largeScalesWrapEveryChipIntoReachableRows() {
        assertTrue(InboxFilterChipLayoutRules.chipsWrapOntoMultipleRows(1.3f));
        assertTrue(InboxFilterChipLayoutRules.chipsWrapOntoMultipleRows(2.0f));
    }

    @Test
    public void narrowWidthWrapsSoArchivedStaysOnScreen() {
        assertTrue(InboxFilterChipLayoutRules.chipsWrapOntoMultipleRows(1.0f, 320));
        assertTrue(InboxFilterChipLayoutRules.chipsWrapOntoMultipleRows(1.0f, 359));
        assertFalse(InboxFilterChipLayoutRules.chipsWrapOntoMultipleRows(1.0f, 360));
        assertFalse(InboxFilterChipLayoutRules.chipsWrapOntoMultipleRows(1.0f, 411));
    }
}
