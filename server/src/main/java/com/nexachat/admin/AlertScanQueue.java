package com.nexachat.admin;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Backpressure boundary between message send (post-commit listener) and the
 * scanner. A bounded {@link ArrayBlockingQueue}: when the producer outruns
 * the drain, {@link #enqueue} drops the task and counts it — scans are
 * best-effort by design and a hot conversation must never stall the send
 * path or grow heap without limit.
 *
 * <p>Queue depth, drops and outcomes are exposed both as counters and through
 * {@link AdminOverviewService}'s queue-health card.
 */
@Component
public class AlertScanQueue {

    private static final Logger log = LoggerFactory.getLogger(AlertScanQueue.class);

    /** One pending scan job — content travels in-memory only, never persisted. */
    public record ScanTask(UUID messageId, UUID conversationId, UUID senderId, long seq,
                           String content, Instant createdAt, boolean rescan) {
    }

    private final BlockingQueue<ScanTask> queue;
    private final AdminProperties properties;
    private final AlertScanService scanService;
    private final MeterRegistry meterRegistry;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong scanned = new AtomicLong();
    private final AtomicLong alertsCreated = new AtomicLong();
    private final AtomicLong optOutSkips = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public AlertScanQueue(AdminProperties properties,
                          AlertScanService scanService,
                          MeterRegistry meterRegistry) {
        this.properties = properties;
        this.scanService = scanService;
        this.meterRegistry = meterRegistry;
        this.queue = new ArrayBlockingQueue<>(Math.max(64, properties.alerts().queueCapacity()));
        meterRegistry.gauge("nexachat.admin.alerts.queue.depth", queue, BlockingQueue::size);
    }

    /**
     * Offers a scan job. {@code false} means the queue was full — the caller
     * logs nothing (the listener fires per message); the drop is counted here.
     */
    public boolean enqueue(ScanTask task) {
        if (!properties.alerts().enabled()) {
            return false;
        }
        boolean accepted = queue.offer(task);
        if (!accepted) {
            long drops = dropped.incrementAndGet();
            meterRegistry.counter("nexachat.admin.alerts.queue.dropped").increment();
            if (drops % 100 == 1) {
                log.warn("Alert scan queue full — dropped {} scan task(s) so far", drops);
            }
        }
        return accepted;
    }

    /** Bounded drain on a fixed delay; one batch keeps latency and memory flat. */
    @Scheduled(fixedDelayString = "${nexachat.admin.alerts.drain-interval-ms:500}")
    public void drain() {
        if (!properties.alerts().enabled()) {
            return;
        }
        int batch = Math.max(1, properties.alerts().batchSize());
        List<ScanTask> tasks = new ArrayList<>(batch);
        queue.drainTo(tasks, batch);
        for (ScanTask task : tasks) {
            try {
                int created = scanService.process(task);
                scanned.incrementAndGet();
                alertsCreated.addAndGet(created);
            } catch (AlertScanService.OptedOutSkip skip) {
                optOutSkips.incrementAndGet();
            } catch (RuntimeException failure) {
                failures.incrementAndGet();
                meterRegistry.counter("nexachat.admin.alerts.scan.failures").increment();
                log.warn("Alert scan failed for a queued message ({})",
                        failure.getClass().getSimpleName());
            }
        }
    }

    public int depth() {
        return queue.size();
    }

    public long dropped() {
        return dropped.get();
    }

    public long scanned() {
        return scanned.get();
    }

    public long alertsCreated() {
        return alertsCreated.get();
    }

    public long optOutSkips() {
        return optOutSkips.get();
    }

    public long failures() {
        return failures.get();
    }
}
