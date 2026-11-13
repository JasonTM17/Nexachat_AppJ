package com.nexachat.feature.inbox;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class InboxFilterChipColourRulesTest {

    @Test
    public void idleChipUsesSurfaceFillAndOutlineVariantStroke() {
        assertEquals("colorSurface", InboxFilterChipColourRules.IDLE_FILL);
        assertEquals("colorOutlineVariant", InboxFilterChipColourRules.IDLE_STROKE);
        assertEquals("colorOnSurfaceVariant", InboxFilterChipColourRules.IDLE_TEXT);
        assertEquals(1f, InboxFilterChipColourRules.IDLE_STROKE_DP, 0f);
    }

    @Test
    public void checkedChipUsesBrandFillTrio() {
        assertEquals("brand_fill", InboxFilterChipColourRules.CHECKED_FILL);
        assertEquals("brand_on_fill", InboxFilterChipColourRules.CHECKED_ON_FILL);
        assertEquals("brand_pressed", InboxFilterChipColourRules.CHECKED_PRESSED_FILL);
    }
}
