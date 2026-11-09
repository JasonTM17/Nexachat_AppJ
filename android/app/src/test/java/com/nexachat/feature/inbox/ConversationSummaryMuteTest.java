package com.nexachat.feature.inbox;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nexachat.data.ConversationSummary;
import org.junit.Test;

public final class ConversationSummaryMuteTest {

    @Test
    public void foreverMuteStaysMuted() {
        assertTrue(summary(true, null).isMutedAt(1_700_000_000_000L));
    }

    @Test
    public void timedMuteExpiresLazilyAgainstTheClock() {
        ConversationSummary muted = summary(true, 2_000L);

        assertTrue(muted.isMutedAt(1_999L));
        assertFalse(muted.isMutedAt(2_000L));
        assertFalse(muted.isMutedAt(2_001L));
    }

    @Test
    public void unmutedRowIgnoresAnyStaleExpiry() {
        assertFalse(summary(false, 2_000L).isMutedAt(1_999L));
    }

    private static ConversationSummary summary(boolean muted, Long mutedUntilEpochMs) {
        return new ConversationSummary(
                "c-1", "DIRECT", null, null, null, null, 0L, 0,
                "Peer", null, null, 0L, 0L, null, "peer-1", false, null,
                muted, mutedUntilEpochMs, false, false);
    }
}
