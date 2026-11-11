package com.nexachat.attachment;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounds how many uploads may hold their whole file on the heap at once.
 *
 * <p>Every upload path buffers the multipart part with
 * {@code MultipartFile.getBytes()} (up to 25 MB) before the storage layer
 * re-sniffs and persists it. On the 512 MB Render box a burst of concurrent
 * uploads would otherwise add several hundred megabytes of byte arrays to the
 * heap and can exhaust it. This gate admits a bounded number of concurrent
 * buffers; the rest wait briefly and then fail with the standard
 * {@code 429 RATE_LIMITED} envelope instead of growing the heap.</p>
 *
 * <p>Configure with {@code nexachat.uploads.max-concurrent} (default 16) and
 * {@code nexachat.uploads.acquire-timeout-ms} (default 1000). The default is
 * deliberately generous for hosts with a normal heap; memory-constrained
 * deployments set it lower.</p>
 */
@Component
public class UploadAdmission {

    /** Default concurrent buffers; sized for a host with a normal-size heap. */
    static final int DEFAULT_MAX_CONCURRENT = 16;

    /** Default wait for a free slot before answering 429. */
    static final long DEFAULT_ACQUIRE_TIMEOUT_MILLIS = 1000;

    private final Semaphore permits;
    private final long acquireTimeoutMillis;

    public UploadAdmission(
            @Value("${nexachat.uploads.max-concurrent:16}") int maxConcurrent,
            @Value("${nexachat.uploads.acquire-timeout-ms:1000}") long acquireTimeoutMillis) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException(
                    "nexachat.uploads.max-concurrent must be at least 1");
        }
        if (acquireTimeoutMillis < 0) {
            throw new IllegalArgumentException(
                    "nexachat.uploads.acquire-timeout-ms must not be negative");
        }
        // Fair ordering: a saturated gate hands the next slot to the longest
        // waiter, so a burst cannot starve an earlier upload.
        this.permits = new Semaphore(maxConcurrent, true);
        this.acquireTimeoutMillis = acquireTimeoutMillis;
    }

    /**
     * Takes one upload slot, waiting at most the configured timeout.
     *
     * @return a permit that must be closed on every path, normally with
     *         try-with-resources
     * @throws ApiException {@code RATE_LIMITED} (429) when no slot frees up in
     *                      time; details carry {@code retryAfterSeconds}
     */
    public Permit acquire() {
        final boolean acquired;
        try {
            acquired = permits.tryAcquire(acquireTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw busy();
        }
        if (!acquired) {
            throw busy();
        }
        return new Permit();
    }

    private ApiException busy() {
        long retryAfterSeconds = Math.max(1L,
                (acquireTimeoutMillis + TimeUnit.SECONDS.toMillis(1) - 1)
                        / TimeUnit.SECONDS.toMillis(1));
        return ApiException.of(ApiErrorCode.RATE_LIMITED,
                "Too many concurrent uploads, try again later",
                Map.of("retryAfterSeconds", retryAfterSeconds));
    }

    /** Visible for tests: how many upload slots are free right now. */
    int availablePermits() {
        return permits.availablePermits();
    }

    /**
     * One admitted upload. Closing it returns the slot exactly once; a double
     * close is a no-op, so a defensive close in a nested handler cannot inflate
     * the semaphore.
     */
    public final class Permit implements AutoCloseable {

        private final AtomicBoolean released = new AtomicBoolean();

        private Permit() {
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                permits.release();
            }
        }
    }
}
