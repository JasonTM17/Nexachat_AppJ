package com.nexachat.auth;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Bucket4j token-bucket rate limiting (plan T15).
 *
 * <p>Buckets live in an in-memory {@link ConcurrentHashMap} keyed per rule+IP —
 * correct for the documented single-node deployment. If NexaChat ever runs more
 * than one backend instance this must move to a shared store (Bucket4j supports
 * Redis/JDBC backends); that limitation is stated in the report rather than hidden.
 */
@Service
public class RateLimitService {

    /** Outcome of one consume attempt; {@code retryAfterSeconds} informs the client when denied. */
    public record Consumption(boolean allowed, long retryAfterSeconds) {
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public Consumption tryConsume(String bucketKey, long capacity, Duration refillPeriod) {
        Bucket bucket = buckets.computeIfAbsent(bucketKey, k -> newBucket(capacity, refillPeriod));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new Consumption(true, 0);
        }
        long retryAfter = Math.max(1L, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
        return new Consumption(false, retryAfter);
    }

    private static Bucket newBucket(long capacity, Duration period) {
        // Greedy refill: the budget trickles back continuously instead of all-at-once
        // at the window boundary, so a burst right after the boundary cannot double-fire.
        Bandwidth limit = Bandwidth.builder()
                .capacity(capacity)
                .refillGreedy(capacity, period)
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    /** Visible for tests: how many distinct keys currently hold a bucket. */
    int bucketCount() {
        return buckets.size();
    }
}
