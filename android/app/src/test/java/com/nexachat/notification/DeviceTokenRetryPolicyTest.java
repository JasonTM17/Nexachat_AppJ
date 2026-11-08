package com.nexachat.notification;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DeviceTokenRetryPolicyTest {

    @Test
    public void allowsInitialAttemptAndFourRetriesThenStops() {
        for (int attempt = 0; attempt < DeviceTokenRetryPolicy.MAX_RETRIES; attempt++) {
            assertTrue(DeviceTokenRetryPolicy.shouldRetry(attempt));
        }
        assertFalse(DeviceTokenRetryPolicy.shouldRetry(DeviceTokenRetryPolicy.MAX_RETRIES));
    }
}
