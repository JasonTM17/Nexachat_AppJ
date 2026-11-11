package com.nexachat.auth;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Bucket4j token-bucket rate limiting (plan T15).
 *
 * <p>Buckets live in a bounded, in-memory LRU map keyed per rule and identity —
 * correct for the documented single-node deployment. If NexaChat ever runs more
 * than one backend instance this must move to a shared store (Bucket4j supports
 * Redis/JDBC backends); that limitation is stated in the report rather than hidden.
 */
@Service
public class RateLimitService {

    static final int DEFAULT_MAX_BUCKETS = 10_000;
    static final Duration DEFAULT_IDLE_TTL = Duration.ofMinutes(15);

    private static final long EXPIRY_SWEEP_INTERVAL = 64;

    /** Outcome of one consume attempt; {@code retryAfterSeconds} informs the client when denied. */
    public record Consumption(boolean allowed, long retryAfterSeconds) {
    }

    private final Map<String, BucketEntry> buckets = new LinkedHashMap<>(16, 0.75f, true);
    private final int maxBuckets;
    private final long idleTtlNanos;
    private final TimeMeter timeMeter;
    private final Object bucketLock = new Object();
    private long operations;

    /**
     * Production constructor: Bucket4j's millisecond system clock, matching the
     * previous default so refill behaviour is unchanged.
     */
    @Autowired
    public RateLimitService(TimeMeter timeMeter) {
        this(DEFAULT_MAX_BUCKETS, DEFAULT_IDLE_TTL, timeMeter);
    }

    /** Standalone use (tests, manual wiring) keeps the production time source. */
    public RateLimitService() {
        this(DEFAULT_MAX_BUCKETS, DEFAULT_IDLE_TTL, TimeMeter.SYSTEM_MILLISECONDS);
    }

    RateLimitService(int maxBuckets, Duration idleTtl) {
        this(maxBuckets, idleTtl, TimeMeter.SYSTEM_MILLISECONDS);
    }

    RateLimitService(int maxBuckets, Duration idleTtl, TimeMeter timeMeter) {
        if (maxBuckets < 1 || idleTtl.isNegative() || idleTtl.isZero()) {
            throw new IllegalArgumentException("Rate-limit cache bounds must be positive");
        }
        this.maxBuckets = maxBuckets;
        this.idleTtlNanos = idleTtl.toNanos();
        this.timeMeter = Objects.requireNonNull(timeMeter, "timeMeter must not be null");
    }

    public Consumption tryConsume(String bucketKey, long capacity, Duration refillPeriod) {
        long now = System.nanoTime();
        Bucket bucket;
        synchronized (bucketLock) {
            if (++operations % EXPIRY_SWEEP_INTERVAL == 0) {
                evictExpired(now);
            }

            BucketEntry entry = buckets.get(bucketKey);
            if (entry != null && now - entry.lastAccessNanos >= idleTtlNanos) {
                buckets.remove(bucketKey);
                entry = null;
            }
            if (entry == null) {
                if (buckets.size() >= maxBuckets) {
                    evictExpired(now);
                    if (buckets.size() >= maxBuckets) {
                        Iterator<String> oldest = buckets.keySet().iterator();
                        oldest.next();
                        oldest.remove();
                    }
                }
                entry = new BucketEntry(newBucket(capacity, refillPeriod), now);
                buckets.put(bucketKey, entry);
            } else {
                entry.lastAccessNanos = now;
            }
            bucket = entry.bucket;
        }

        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new Consumption(true, 0);
        }
        long retryAfter = Math.max(1L, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
        return new Consumption(false, retryAfter);
    }

    private Bucket newBucket(long capacity, Duration period) {
        // Greedy refill: the budget trickles back continuously instead of all-at-once
        // at the window boundary, so a burst right after the boundary cannot double-fire.
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, period)
                .build();
        return Bucket.builder()
                .addLimit(limit)
                .withCustomTimePrecision(timeMeter)
                .build();
    }

    private void evictExpired(long now) {
        Iterator<Map.Entry<String, BucketEntry>> iterator = buckets.entrySet().iterator();
        while (iterator.hasNext()) {
            BucketEntry entry = iterator.next().getValue();
            if (now - entry.lastAccessNanos >= idleTtlNanos) {
                iterator.remove();
            }
        }
    }

    private static final class BucketEntry {
        private final Bucket bucket;
        private long lastAccessNanos;

        private BucketEntry(Bucket bucket, long lastAccessNanos) {
            this.bucket = bucket;
            this.lastAccessNanos = lastAccessNanos;
        }
    }

    /** Visible for tests: how many distinct keys currently hold a bucket. */
    int bucketCount() {
        synchronized (bucketLock) {
            return buckets.size();
        }
    }

    /** Drops every bucket so a case starts from an empty cache (tests and ITs). */
    public void reset() {
        synchronized (bucketLock) {
            buckets.clear();
        }
    }
}
