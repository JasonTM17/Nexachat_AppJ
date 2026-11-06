package com.nexachat.core.network.stomp;

import org.junit.Test;

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
}
