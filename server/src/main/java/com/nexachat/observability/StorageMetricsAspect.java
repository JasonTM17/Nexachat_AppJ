package com.nexachat.observability;

import com.nexachat.attachment.StorageService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Times storage operations and separates the time to open a stream from the
 * time the caller spends consuming it.
 *
 * <p>An aspect is simpler than a decorator here: both backends are already
 * beans behind {@link StorageService}, the backend is selected by
 * {@code nexachat.storage.provider}, and the rest of the metrics surface uses
 * aspects too. The advice never reads a storage key, filename or URL; it only
 * records the operation name, provider kind, byte count and outcome.</p>
 *
 * <p>The provider label is fixed to {@code local} or {@code s3}; unknown
 * configuration cannot widen the label set because
 * {@code StorageProviderConfiguration} rejects it before this bean is created.</p>
 */
@Aspect
@Component
public class StorageMetricsAspect {

    static final String STORE = "STORE";
    static final String OPEN = "OPEN";
    static final String OPEN_RANGE = "OPEN_RANGE";
    static final String DELETE = "DELETE";
    static final String LOCAL = "local";
    static final String S3 = "s3";

    private final ObservabilityMetrics metrics;
    private final String provider;

    public StorageMetricsAspect(ObservabilityMetrics metrics,
                                @Value("${nexachat.storage.provider:local}") String provider) {
        this.metrics = metrics;
        this.provider = S3.equalsIgnoreCase(provider) ? S3 : LOCAL;
    }

    @Around("execution(* com.nexachat.attachment.StorageService+.store(..))")
    public Object recordStore(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.nanoTime();
        long bytes = byteArrayLength(joinPoint.getArgs(), 3);
        try {
            Object result = joinPoint.proceed();
            long storedBytes = result instanceof StorageService.StoredFile stored
                    ? stored.sizeBytes() : bytes;
            metrics.recordStorageOperation(STORE, provider, elapsedMillis(start), storedBytes, true);
            return result;
        } catch (Throwable failure) {
            metrics.recordStorageOperation(STORE, provider, elapsedMillis(start), bytes, false);
            throw failure;
        }
    }

    @Around("execution(* com.nexachat.attachment.StorageService+.open(..))")
    public Object recordOpen(ProceedingJoinPoint joinPoint) throws Throwable {
        return recordOpen(joinPoint, OPEN);
    }

    @Around("execution(* com.nexachat.attachment.StorageService+.openRange(..))")
    public Object recordOpenRange(ProceedingJoinPoint joinPoint) throws Throwable {
        return recordOpen(joinPoint, OPEN_RANGE);
    }

    @Around("execution(* com.nexachat.attachment.StorageService+.deleteIfExists(..))")
    public Object recordDelete(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.nanoTime();
        try {
            Object result = joinPoint.proceed();
            metrics.recordStorageOperation(DELETE, provider, elapsedMillis(start), 0, true);
            return result;
        } catch (Throwable failure) {
            metrics.recordStorageOperation(DELETE, provider, elapsedMillis(start), 0, false);
            throw failure;
        }
    }

    private Object recordOpen(ProceedingJoinPoint joinPoint, String operation) throws Throwable {
        long start = System.nanoTime();
        Object result;
        try {
            result = joinPoint.proceed();
        } catch (Throwable failure) {
            metrics.recordStorageOperation(operation, provider, elapsedMillis(start), 0, false);
            throw failure;
        }
        if (result instanceof StorageService.StreamHandle handle) {
            metrics.recordStorageOperation(operation, provider, elapsedMillis(start),
                    handle.sizeBytes(), true);
            return new StorageService.StreamHandle(
                    new MeasuredInputStream(handle.content(), operation, provider, metrics),
                    handle.sizeBytes());
        }
        metrics.recordStorageOperation(operation, provider, elapsedMillis(start), 0, true);
        return result;
    }

    private static long byteArrayLength(Object[] args, int index) {
        if (args != null && index < args.length && args[index] instanceof byte[] bytes) {
            return bytes.length;
        }
        return 0;
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /**
     * Counts bytes and records the terminal stream outcome exactly once.
     *
     * <p>Reading to EOF records {@code COMPLETE}; closing first records
     * {@code CLOSED}; an I/O or runtime error records {@code FAILURE}. No event
     * is recorded when the caller neither reads nor closes, which is the same
     * point at which the underlying resource is still held.</p>
     */
    private static final class MeasuredInputStream extends FilterInputStream {

        private final String operation;
        private final String provider;
        private final ObservabilityMetrics metrics;
        private final long startedAt = System.nanoTime();
        private final AtomicBoolean finished = new AtomicBoolean();
        private long bytes;

        MeasuredInputStream(InputStream content, String operation, String provider,
                            ObservabilityMetrics metrics) {
            super(content);
            this.operation = operation;
            this.provider = provider;
            this.metrics = metrics;
        }

        @Override
        public int read() throws IOException {
            try {
                int value = super.read();
                if (value < 0) {
                    finish(ObservabilityMetrics.StreamOutcome.COMPLETE);
                } else {
                    bytes++;
                }
                return value;
            } catch (IOException failure) {
                finish(ObservabilityMetrics.StreamOutcome.FAILURE);
                throw failure;
            } catch (RuntimeException failure) {
                finish(ObservabilityMetrics.StreamOutcome.FAILURE);
                throw failure;
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            try {
                int read = super.read(buffer, offset, length);
                if (read < 0) {
                    finish(ObservabilityMetrics.StreamOutcome.COMPLETE);
                } else {
                    bytes += read;
                }
                return read;
            } catch (IOException failure) {
                finish(ObservabilityMetrics.StreamOutcome.FAILURE);
                throw failure;
            } catch (RuntimeException failure) {
                finish(ObservabilityMetrics.StreamOutcome.FAILURE);
                throw failure;
            }
        }

        @Override
        public long skip(long count) throws IOException {
            try {
                long skipped = super.skip(count);
                bytes += skipped;
                return skipped;
            } catch (IOException failure) {
                finish(ObservabilityMetrics.StreamOutcome.FAILURE);
                throw failure;
            } catch (RuntimeException failure) {
                finish(ObservabilityMetrics.StreamOutcome.FAILURE);
                throw failure;
            }
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } catch (IOException failure) {
                finish(ObservabilityMetrics.StreamOutcome.FAILURE);
                throw failure;
            } catch (RuntimeException failure) {
                finish(ObservabilityMetrics.StreamOutcome.FAILURE);
                throw failure;
            } finally {
                finish(ObservabilityMetrics.StreamOutcome.CLOSED);
            }
        }

        private void finish(ObservabilityMetrics.StreamOutcome outcome) {
            if (finished.compareAndSet(false, true)) {
                metrics.recordStorageStream(operation, provider, elapsedMillis(startedAt),
                        bytes, outcome);
            }
        }
    }
}
