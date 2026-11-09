package com.nexachat.feature.inbox.adapter;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class MuteIndicatorRulesTest {
    @Test
    public void schedulesRefreshAtFutureMuteExpiryOnly() {
        assertEquals(500L, MuteIndicatorRules.refreshDelayMillis(true, 1_500L, 1_000L));
        assertEquals(0L, MuteIndicatorRules.refreshDelayMillis(true, 1_000L, 1_000L));
        assertEquals(0L, MuteIndicatorRules.refreshDelayMillis(true, 999L, 1_000L));
        assertEquals(0L, MuteIndicatorRules.refreshDelayMillis(false, 1_500L, 1_000L));
        assertEquals(0L, MuteIndicatorRules.refreshDelayMillis(true, null, 1_000L));
    }
}
