package com.nexachat.feature.summary.adapter;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class SummaryCitationLabelsTest {

    @Test
    public void fallsBackToMessageIdPrefix() {
        assertEquals("abcd", SummaryCitationLabels.messageIdPrefix("abcdef"));
    }

    @Test
    public void truncatesShortMessageIds() {
        assertEquals("ab", SummaryCitationLabels.messageIdPrefix("ab"));
    }
}
