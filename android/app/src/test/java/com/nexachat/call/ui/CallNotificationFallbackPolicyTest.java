package com.nexachat.call.ui;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CallNotificationFallbackPolicyTest {
    @Test
    public void failedNotificationPostingAttemptsActivityFallback() {
        assertTrue(CallNotificationFallbackPolicy.shouldAttemptActivityFallback(false));
    }

    @Test
    public void successfullyPostedNotificationDoesNotLaunchDuplicateActivity() {
        assertFalse(CallNotificationFallbackPolicy.shouldAttemptActivityFallback(true));
    }
}
