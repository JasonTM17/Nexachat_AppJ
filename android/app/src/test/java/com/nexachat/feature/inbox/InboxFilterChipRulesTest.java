package com.nexachat.feature.inbox;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class InboxFilterChipRulesTest {

    @Test
    public void showLabelFilters_isFalseWhenNoCustomLabels() {
        assertFalse(InboxFilterChipRules.showLabelFilterRow(0));
    }

    @Test
    public void showLabelFilters_isTrueWhenLabelsExist() {
        assertTrue(InboxFilterChipRules.showLabelFilterRow(1));
        assertTrue(InboxFilterChipRules.showLabelFilterRow(3));
    }
}
