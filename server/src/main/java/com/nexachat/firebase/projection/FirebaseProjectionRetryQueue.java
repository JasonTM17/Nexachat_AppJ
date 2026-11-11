package com.nexachat.firebase.projection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded in-memory retries; every operation re-reads PostgreSQL through its projection service method. */
public class FirebaseProjectionRetryQueue {

    private static final Logger log = LoggerFactory.getLogger(FirebaseProjectionRetryQueue.class);
    private static final int MAX_PENDING = 10_000;
    private static final long MAX_DELAY_MILLIS = 300_000;

    private final Clock clock;
    private final FirebaseProjectionMetrics metrics;
    private final Map<String, Retry> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();

    public FirebaseProjectionRetryQueue(Clock clock, FirebaseProjectionMetrics metrics) {
        this.clock = clock;
        this.metrics = metrics;
    }

    public synchronized void enqueue(String key, Runnable operation) {
        pending.compute(key, (ignored, existing) -> {
            if (existing != null) {
                return existing;
            }
            if (pending.size() >= MAX_PENDING) {
                metrics.retry("overflow");
                log.error("Firebase authorization retry queue reached its capacity");
                return null;
            }
            metrics.retry("queued");
            return new Retry(operation, clock.instant().plusMillis(jitter(1_000)), 0);
        });
        metrics.retryBacklog(pending.size());
    }

    @Scheduled(fixedDelayString = "${nexachat.firebase.projection.retry-interval-ms:1000}")
    public void retryDue() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            Instant now = clock.instant();
            pending.entrySet().stream()
                    .filter(entry -> !entry.getValue().nextAttempt().isAfter(now))
                    .limit(100)
                    .toList().forEach(entry -> {
                String key = entry.getKey();
                Retry retry = entry.getValue();
                if (pending.remove(key, retry)) {
                    metrics.retryBacklog(pending.size());
                    try {
                        retry.operation().run();
                        metrics.retry("success");
                    } catch (RuntimeException failure) {
                        long base = Math.min(MAX_DELAY_MILLIS,
                                1_000L << Math.min(8, retry.failures() + 1));
                        pending.putIfAbsent(key, new Retry(retry.operation(),
                                clock.instant().plusMillis(jitter(base)), retry.failures() + 1));
                        metrics.retryBacklog(pending.size());
                        metrics.retry("failure");
                        log.warn("Firebase authorization retry remains pending ({})",
                                failure.getClass().getSimpleName());
                    }
                }
            });
        } finally {
            running.set(false);
        }
    }

    int pendingCount() {
        return pending.size();
    }

    private static long jitter(long baseMillis) {
        long lower = Math.max(1, baseMillis / 2);
        long upper = Math.max(lower + 1, baseMillis + 1);
        return ThreadLocalRandom.current().nextLong(lower, upper);
    }

    private record Retry(Runnable operation, Instant nextAttempt, int failures) {
    }
}
