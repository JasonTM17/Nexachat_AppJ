package com.nexachat.feature.inbox;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class InboxFilterChipColourRulesTest {

    @Test
    public void idleChipUsesSurfaceContainerAndOutlineVariantStroke() {
        assertEquals("colorSurfaceContainer", InboxFilterChipColourRules.IDLE_CONTAINER);
        assertEquals("colorOutlineVariant", InboxFilterChipColourRules.IDLE_STROKE);
        assertEquals("colorOnSurfaceVariant", InboxFilterChipColourRules.IDLE_TEXT);
        assertEquals(1f, InboxFilterChipColourRules.IDLE_STROKE_DP, 0f);
    }

    @Test
    public void checkedChipUsesPrimaryContainerTrio() {
        assertEquals("colorPrimaryContainer", InboxFilterChipColourRules.CHECKED_CONTAINER);
        assertEquals("colorOnPrimaryContainer", InboxFilterChipColourRules.CHECKED_ON_CONTAINER);
        assertEquals("nexa_pressed_container", InboxFilterChipColourRules.CHECKED_PRESSED_CONTAINER);
        assertEquals("colorPrimary", InboxFilterChipColourRules.CHECKED_STROKE);
    }
}
