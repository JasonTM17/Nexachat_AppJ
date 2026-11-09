package com.nexachat.feature.label;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class LabelRulesTest {
    @Test
    public void labelNameLimitCountsUnicodeCodePoints() {
        assertTrue(LabelRules.isValidName("😀".repeat(30)));
        assertFalse(LabelRules.isValidName("😀".repeat(31)));
        assertFalse(LabelRules.isValidName("   "));
    }

    @Test
    public void duplicateNamesIgnoreCaseAndOuterWhitespace() {
        List<ConversationLabel> labels = List.of(
                new ConversationLabel("work", "  Work ", LabelColor.BLUE, false));

        assertTrue(LabelRules.hasDuplicateName("work", labels, null));
        assertFalse(LabelRules.hasDuplicateName("Work", labels, "work"));
        assertFalse(LabelRules.hasDuplicateName("Home", labels, null));
    }
}
