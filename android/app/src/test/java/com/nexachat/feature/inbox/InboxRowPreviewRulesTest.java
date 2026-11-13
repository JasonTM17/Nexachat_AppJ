package com.nexachat.feature.inbox;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class InboxRowPreviewRulesTest {

    @Test
    public void hasPreviewLine_isFalseForBlankOrNull() {
        assertFalse(InboxRowPreviewRules.hasPreviewLine(null));
        assertFalse(InboxRowPreviewRules.hasPreviewLine(""));
        assertFalse(InboxRowPreviewRules.hasPreviewLine("   "));
    }

    @Test
    public void hasPreviewLine_isTrueWhenTextPresent() {
        assertTrue(InboxRowPreviewRules.hasPreviewLine("Latest"));
        assertTrue(InboxRowPreviewRules.hasPreviewLine(" x "));
    }
}
