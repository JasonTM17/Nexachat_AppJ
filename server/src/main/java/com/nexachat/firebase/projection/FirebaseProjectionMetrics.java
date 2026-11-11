package com.nexachat.firebase.projection;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.atomic.AtomicInteger;

/** Firebase projection metrics use only finite operation/outcome labels. */
public class FirebaseProjectionMetrics {

    private static final String WRITE_COUNT = "nexachat.firebase.projection.writes";
    private static final String WRITE_DURATION = "nexachat.firebase.projection.write.duration";
    private static final String RETRIES = "nexachat.firebase.projection.retries";
    private static final String RETRY_BACKLOG = "nexachat.firebase.projection.retry.backlog";
    private static final String BACKFILL_ROWS = "nexachat.firebase.projection.backfill.rows";
    private static final String EXPIRED_ROWS = "nexachat.firebase.projection.expired.rows";

    private final MeterRegistry registry;
    private final AtomicInteger retryBacklog = new AtomicInteger();

    public FirebaseProjectionMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder(RETRY_BACKLOG, retryBacklog, AtomicInteger::get).register(registry);
    }

    public void write(String kind, Runnable operation) {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "success";
        try {
            operation.run();
        } catch (RuntimeException failure) {
            outcome = "failure";
            throw failure;
        } finally {
            Counter.builder(WRITE_COUNT).tag("kind", kind).tag("outcome", outcome).register(registry).increment();
            sample.stop(Timer.builder(WRITE_DURATION).tag("kind", kind).register(registry));
        }
    }

    public void retry(String outcome) {
        Counter.builder(RETRIES).tag("outcome", outcome).register(registry).increment();
    }

    public void retryBacklog(int size) {
        retryBacklog.set(size);
    }

    public void backfillRows(String kind, int count) {
        if (count > 0) {
            Counter.builder(BACKFILL_ROWS).tag("kind", kind).register(registry).increment(count);
        }
    }

    public void expiredRows(String kind, int count) {
        if (count > 0) {
            Counter.builder(EXPIRED_ROWS).tag("kind", kind).register(registry).increment(count);
        }
    }
}
