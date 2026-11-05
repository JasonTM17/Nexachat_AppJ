package com.nexachat.core.ui;

/**
 * Pure presentation logic helper for peer presence clock ticking.
 * Computes whether the presence ticker should run and calculates delays
 * to next minute boundaries for relative last-seen updates.
 * Written in Java per project language rules.
 */
public final class PresenceTickerHelper {

    public static final long ONE_MINUTE_MS = 60_000L;
    public static final long TICK_SAFETY_BUFFER_MS = 50L;

    private PresenceTickerHelper() {}

    /**
     * Determines whether the UI-local presence clock ticker should run.
     * Only ticks for DIRECT chats that are connected, peer not typing, peer offline,
     * and lastSeenAt timestamp available.
     */
    public static boolean shouldTick(
            boolean isGroup,
            boolean connected,
            boolean peerTyping,
            Boolean peerOnline,
            Long lastSeenAtEpochMs
    ) {
        if (isGroup) {
            return false;
        }
        if (!connected) {
            return false;
        }
        if (peerTyping) {
            return false;
        }
        if (peerOnline == null || peerOnline) {
            return false;
        }
        return lastSeenAtEpochMs != null && lastSeenAtEpochMs > 0L;
    }

    /**
     * Computes milliseconds to wait until the next minute boundary transition
     * of relative presence time (nowEpochMs - lastSeenAtEpochMs).
     *
     * @param nowEpochMs current epoch millisecond
     * @param lastSeenAtEpochMs last seen epoch millisecond
     * @return delay in milliseconds until next minute transition
     */
    public static long computeNextDelayMs(long nowEpochMs, long lastSeenAtEpochMs) {
        if (lastSeenAtEpochMs <= 0L) {
            return ONE_MINUTE_MS;
        }
        long diffMs = nowEpochMs - lastSeenAtEpochMs;
        if (diffMs < 0L) {
            long waitToPresent = (lastSeenAtEpochMs - nowEpochMs) + ONE_MINUTE_MS + TICK_SAFETY_BUFFER_MS;
            return Math.max(1_000L, Math.min(ONE_MINUTE_MS, waitToPresent));
        }

        long elapsedInMinute = diffMs % ONE_MINUTE_MS;
        long remaining = ONE_MINUTE_MS - elapsedInMinute;
        long delayWithBuffer = remaining + TICK_SAFETY_BUFFER_MS;

        return Math.max(25L, Math.min(ONE_MINUTE_MS + TICK_SAFETY_BUFFER_MS, delayWithBuffer));
    }
}
