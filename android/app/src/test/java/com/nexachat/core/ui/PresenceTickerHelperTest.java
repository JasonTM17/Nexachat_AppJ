package com.nexachat.core.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PresenceTickerHelperTest {

    @Test
    public void shouldTick_returnsFalseForGroupChat() {
        boolean result = PresenceTickerHelper.shouldTick(
                true,   // isGroup
                true,   // connected
                false,  // peerTyping
                false,  // peerOnline
                1000L   // lastSeen
        );
        assertFalse(result);
    }

    @Test
    public void shouldTick_returnsFalseWhenDisconnected() {
        boolean result = PresenceTickerHelper.shouldTick(
                false,
                false,  // connected
                false,
                false,
                1000L
        );
        assertFalse(result);
    }

    @Test
    public void shouldTick_returnsFalseWhenPeerTyping() {
        boolean result = PresenceTickerHelper.shouldTick(
                false,
                true,
                true,   // peerTyping
                false,
                1000L
        );
        assertFalse(result);
    }

    @Test
    public void shouldTick_returnsFalseWhenPeerOnlineOrNull() {
        assertFalse(PresenceTickerHelper.shouldTick(false, true, false, true, 1000L));
        assertFalse(PresenceTickerHelper.shouldTick(false, true, false, null, 1000L));
    }

    @Test
    public void shouldTick_returnsFalseWhenLastSeenMissingOrInvalid() {
        assertFalse(PresenceTickerHelper.shouldTick(false, true, false, false, null));
        assertFalse(PresenceTickerHelper.shouldTick(false, true, false, false, 0L));
        assertFalse(PresenceTickerHelper.shouldTick(false, true, false, false, -500L));
    }

    @Test
    public void shouldTick_returnsTrueWhenDirectConnectedOfflineWithValidTimestamp() {
        assertTrue(PresenceTickerHelper.shouldTick(false, true, false, false, 12345678L));
    }

    @Test
    public void computeNextDelayMs_returnsBufferAtMinuteStart() {
        long lastSeen = 1_000_000L;
        long now = 1_000_000L; // diff = 0
        long delayMs = PresenceTickerHelper.computeNextDelayMs(now, lastSeen);
        assertEquals(60_050L, delayMs);
    }

    @Test
    public void computeNextDelayMs_calculatesRemainingSecondsAccurately() {
        long lastSeen = 1_000_000L;
        long now = 1_045_000L; // diff = 45s, 15s remaining
        long delayMs = PresenceTickerHelper.computeNextDelayMs(now, lastSeen);
        assertEquals(15_050L, delayMs);
    }

    @Test
    public void computeNextDelayMs_handlesNearMinuteBoundary() {
        long lastSeen = 1_000_000L;
        long now = 1_059_990L; // diff = 59s 990ms (10ms remaining)
        long delayMs = PresenceTickerHelper.computeNextDelayMs(now, lastSeen);
        assertEquals(60L, delayMs); // 10ms + 50ms buffer = 60ms
    }

    @Test
    public void computeNextDelayMs_handlesNegativeDiffOrInvalidTimestampGracefully() {
        assertEquals(60_000L, PresenceTickerHelper.computeNextDelayMs(1000L, 0L));
        long delayFuture = PresenceTickerHelper.computeNextDelayMs(1_000_000L, 1_010_000L);
        assertTrue(delayFuture >= 1_000L && delayFuture <= 60_000L);
    }
}
