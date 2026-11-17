package com.nexachat.feature.feed;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class FeedTimeRulesTest {

    @Test public void parsesAnIsoInstant() {
        assertEquals(1_771_997_600_000L,
                FeedTimeRules.epochMillis("2026-02-25T05:33:20Z"));
    }

    @Test public void parsesFractionalSeconds() {
        assertEquals(1_771_997_600_500L,
                FeedTimeRules.epochMillis("2026-02-25T05:33:20.500Z"));
    }

    @Test public void missingOrBlankValuesBecomeZero() {
        assertEquals(0L, FeedTimeRules.epochMillis(null));
        assertEquals(0L, FeedTimeRules.epochMillis(""));
        assertEquals(0L, FeedTimeRules.epochMillis("   "));
    }

    @Test public void malformedValuesBecomeZero() {
        assertEquals(0L, FeedTimeRules.epochMillis("25/02/2026"));
        assertEquals(0L, FeedTimeRules.epochMillis("2026-02-25"));
        assertEquals(0L, FeedTimeRules.epochMillis("not-a-time"));
    }
}
