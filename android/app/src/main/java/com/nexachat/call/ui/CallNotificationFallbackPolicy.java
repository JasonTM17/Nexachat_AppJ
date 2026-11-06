package com.nexachat.call.ui;

/** Selects the activity fallback when an incoming notification cannot be posted. */
public final class CallNotificationFallbackPolicy {
    private CallNotificationFallbackPolicy() { }

    public static boolean shouldAttemptActivityFallback(boolean notificationPosted) {
        return !notificationPosted;
    }
}
