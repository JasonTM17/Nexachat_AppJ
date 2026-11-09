package com.nexachat.core.network.stomp;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ReconnectPolicyTest {

    @Test
    public void delayUsesExponentialBackoffWithinJitterBoundsAndCap() {
        ReconnectPolicy policy = new ReconnectPolicy();

        for (int attempt = 1; attempt <= ReconnectPolicy.MAX_ATTEMPTS; attempt++) {
            long base = ReconnectPolicy.BASE_MS * (1L << (attempt - 1));
            long min = (long) (base * 0.8);
            long max = Math.min((long) (base * 1.2), ReconnectPolicy.MAX_DELAY_MS);
            long delay = policy.delayMillis(attempt);

            assertTrue("attempt " + attempt + " was below jitter range", delay >= min);
            assertTrue("attempt " + attempt + " exceeded jitter range", delay <= max);
        }
    }

    @Test
    public void attemptsPastTheBackoffCapStayWithinMaximumDelay() {
        ReconnectPolicy policy = new ReconnectPolicy();

        for (int attempt : new int[] {6, 20, Integer.MAX_VALUE}) {
            long delay = policy.delayMillis(attempt);
            assertTrue("delay must remain positive", delay >= 12_800L);
            assertTrue("delay must respect the cap", delay <= ReconnectPolicy.MAX_DELAY_MS);
        }
    }

    @Test
    public void retryStopsAtTheConfiguredConsecutiveFailureLimit() {
        ReconnectPolicy policy = new ReconnectPolicy();

        assertTrue(policy.shouldRetry(0));
        assertTrue(policy.shouldRetry(ReconnectPolicy.MAX_ATTEMPTS - 1));
        assertFalse(policy.shouldRetry(ReconnectPolicy.MAX_ATTEMPTS));
        assertFalse(policy.shouldRetry(ReconnectPolicy.MAX_ATTEMPTS + 1));
    }

    @Test
    public void parkedDelayUsesExponentialBackoffWithJitterAndRespectsCap() {
        ReconnectPolicy policy = new ReconnectPolicy();

        for (int round = 1; round <= 4; round++) {
            long base = RealtimeSession.PARKED_RETRY_BASE_MS * (1L << (round - 1));
            long min = (long) (base * 0.8);
            long max = Math.min((long) (base * 1.2), RealtimeSession.PARKED_RETRY_MAX_MS);
            long delay = policy.parkedDelayMillis(round);

            assertTrue("round " + round + " was below jitter range", delay >= min);
            assertTrue("round " + round + " exceeded jitter range", delay <= max);
        }

        for (int round : new int[] {5, 6, 20, Integer.MAX_VALUE}) {
            long delay = policy.parkedDelayMillis(round);
            assertTrue("parked delay must remain positive", delay >= 48_000L);
            assertTrue("parked delay must respect the cap", delay <= RealtimeSession.PARKED_RETRY_MAX_MS);
        }
    }

    @Test
    public void parkedDelayDeterministicWithInjectedJitter() {
        ReconnectPolicy positiveJitter = new ReconnectPolicy(() -> 0.15);
        assertEquals(5_750L, positiveJitter.parkedDelayMillis(1));

        ReconnectPolicy negativeJitter = new ReconnectPolicy(() -> -0.15);
        assertEquals(4_250L, negativeJitter.parkedDelayMillis(1));

        ReconnectPolicy zeroJitter = new ReconnectPolicy(() -> 0.0);
        assertEquals(5_000L, zeroJitter.parkedDelayMillis(1));
        assertEquals(60_000L, zeroJitter.parkedDelayMillis(5));

        // Capped even with maximum positive jitter
        ReconnectPolicy maxJitter = new ReconnectPolicy(() -> 0.20);
        assertEquals(RealtimeSession.PARKED_RETRY_MAX_MS, maxJitter.parkedDelayMillis(5));
    }
}
