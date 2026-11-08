package com.nexachat.notification;

/** Bounded retry policy shared by device-token work and its JVM tests. */
public final class DeviceTokenRetryPolicy {
    public static final int MAX_RETRIES = 4;

    private DeviceTokenRetryPolicy() { }

    /** WorkManager attempt zero is the initial execution, followed by four retries. */
    public static boolean shouldRetry(int runAttemptCount) {
        return runAttemptCount < MAX_RETRIES;
    }
}
