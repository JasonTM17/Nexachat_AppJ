package com.nexachat.feature.summary.adapter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class SummaryCitationLabelsTest {

    @Test
    public void prefersSequenceChipLabel() {
        assertEquals("#42", SummaryCitationLabels.chipLabel(42L, "abcdef"));
    }

    @Test
    public void fallsBackToMessageIdPrefix() {
        assertEquals("abcd", SummaryCitationLabels.chipLabel(null, "abcdef"));
    }
}
