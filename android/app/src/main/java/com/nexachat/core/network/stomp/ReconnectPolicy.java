package com.nexachat.core.network.stomp;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

import javax.inject.Inject;

/**
 * Reconnect backoff calculator: 1s, 2s, 4s, 8s, 16s capped, ±20% jitter, at
 * most {@link #MAX_ATTEMPTS} consecutive attempts before the caller switches
 * to offline mode. The reconnect loop stays in {@code RealtimeSession}.
 */
public final class ReconnectPolicy {

    public static final int MAX_ATTEMPTS = 5;
    public static final long BASE_MS = 1_000L;
    public static final long MAX_DELAY_MS = 16_000L;
    public static final long MIN_DELAY_MS = 100L;
    private static final int MAX_SHIFT = 4;
    private static final double JITTER = 0.2;

    private final DoubleSupplier jitterSource;

    @Inject
    public ReconnectPolicy() {
        this(() -> ThreadLocalRandom.current().nextDouble(-JITTER, JITTER));
    }

    /** Deterministic-jitter constructor for tests. Dagger never sees it. */
    ReconnectPolicy(DoubleSupplier jitterSource) {
        this.jitterSource = jitterSource;
    }

    /**
     * Delay before retry number {@code failedAttempts} (1-based): 1000ms, then
     * doubling up to a 16000ms cap, each ±20% jitter.
     */
    public long delayMillis(int failedAttempts) {
        int shift = Math.max(0, Math.min(failedAttempts - 1, MAX_SHIFT));
        long base = BASE_MS << shift;
        double jittered = base * (1.0 + jitterSource.getAsDouble());
        long value = (long) jittered;
        if (value < MIN_DELAY_MS) {
            return MIN_DELAY_MS;
        }
        if (value > MAX_DELAY_MS) {
            return MAX_DELAY_MS;
        }
        return value;
    }

    /** Whether another attempt is allowed after {@code failedAttempts} failures. */
    public boolean shouldRetry(int failedAttempts) {
        return failedAttempts < MAX_ATTEMPTS;
    }
}
