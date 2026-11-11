package com.nexachat.testutil;

import io.github.bucket4j.TimeMeter;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A Bucket4j {@link TimeMeter} that only moves when a test moves it.
 *
 * <p>Rate-limit tests need this because the buckets refill greedily: asserting
 * "request N is denied" only holds while the bucket clock is frozen. Production
 * uses the system clock through {@code RateLimitConfiguration}.
 */
public final class MutableTimeMeter implements TimeMeter {

    private final AtomicLong nanos;

    public MutableTimeMeter() {
        this(1_000_000_000L);
    }

    public MutableTimeMeter(long startNanos) {
        this.nanos = new AtomicLong(startNanos);
    }

    @Override
    public long currentTimeNanos() {
        return nanos.get();
    }

    @Override
    public boolean isWallClockBased() {
        return false;
    }

    /** Moves the bucket clock forward; must not be negative. */
    public void advance(Duration amount) {
        if (amount.isNegative()) {
            throw new IllegalArgumentException("time must not move backwards");
        }
        nanos.addAndGet(amount.toNanos());
    }
}
