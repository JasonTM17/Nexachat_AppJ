package com.nexachat.observability;

import com.nexachat.attachment.LocalStorageService;
import com.nexachat.attachment.StorageService;
import com.nexachat.common.error.ApiException;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the aspect around a real local backend. The real service is
 * important: the test proves the pointcut, the wrapped stream and the counters
 * without mocking the storage behaviour under test.
 */
class StorageMetricsAspectTest {

    private static final byte[] PNG = hex("89504E470D0A1A0A0000000D49484452");

    @TempDir
    Path tempDir;

    private SimpleMeterRegistry registry;
    private StorageService storage;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        ObservabilityMetrics metrics = new ObservabilityMetrics(registry);
        LocalStorageService local = new LocalStorageService(tempDir.toString());
        AspectJProxyFactory factory = new AspectJProxyFactory(local);
        factory.addAspect(new StorageMetricsAspect(metrics, "local"));
        storage = factory.getProxy();
    }

    @Test
    void separatesOpenDurationFromStreamConsumption() throws Exception {
        StorageService.StoredFile stored =
                storage.store("attachments", "pixel.png", "image/png", PNG);

        assertThat(operationCount(StorageMetricsAspect.STORE, "SUCCESS")).isEqualTo(1);
        assertThat(operationDurationCount(StorageMetricsAspect.STORE, "SUCCESS")).isEqualTo(1);
        assertThat(operationBytes(StorageMetricsAspect.STORE, "SUCCESS")).isEqualTo(PNG.length);

        try (StorageService.StreamHandle handle = storage.open(stored.storageKey())) {
            assertThat(operationCount(StorageMetricsAspect.OPEN, "SUCCESS")).isEqualTo(1);
            assertThat(streamDurationCount(StorageMetricsAspect.OPEN, "COMPLETE")).isZero();

            assertThat(handle.content().readAllBytes()).isEqualTo(PNG);
        }

        assertThat(streamDurationCount(StorageMetricsAspect.OPEN, "COMPLETE")).isEqualTo(1);
        assertThat(streamBytes(StorageMetricsAspect.OPEN, "COMPLETE")).isEqualTo(PNG.length);

        assertThat(storage.deleteIfExists(stored.storageKey())).isTrue();
        assertThat(operationCount(StorageMetricsAspect.DELETE, "SUCCESS")).isEqualTo(1);
    }

    @Test
    void localFailureThenRecoveryIsVisibleInTheOperationCounters() throws Exception {
        assertThatThrownBy(() -> storage.open("attachments/missing.png"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> storage.store("attachments", "setup.exe",
                "application/octet-stream", hex("4D5A90000300000004000000")))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> storage.deleteIfExists("../escape.png"))
                .isInstanceOf(ApiException.class);

        assertThat(operationCount(StorageMetricsAspect.OPEN, "FAILURE")).isEqualTo(1);
        assertThat(operationCount(StorageMetricsAspect.STORE, "FAILURE")).isEqualTo(1);
        assertThat(operationCount(StorageMetricsAspect.DELETE, "FAILURE")).isEqualTo(1);

        StorageService.StoredFile stored =
                storage.store("attachments", "pixel.png", "image/png", PNG);
        try (StorageService.StreamHandle handle = storage.open(stored.storageKey())) {
            handle.content().readAllBytes();
        }
        assertThat(storage.deleteIfExists(stored.storageKey())).isTrue();

        assertThat(operationCount(StorageMetricsAspect.OPEN, "FAILURE")).isEqualTo(1);
        assertThat(operationCount(StorageMetricsAspect.OPEN, "SUCCESS")).isEqualTo(1);
        assertThat(operationCount(StorageMetricsAspect.STORE, "FAILURE")).isEqualTo(1);
        assertThat(operationCount(StorageMetricsAspect.STORE, "SUCCESS")).isEqualTo(1);
        assertThat(operationCount(StorageMetricsAspect.DELETE, "FAILURE")).isEqualTo(1);
        assertThat(operationCount(StorageMetricsAspect.DELETE, "SUCCESS")).isEqualTo(1);
    }

    @Test
    void earlyCloseIsRecordedAsClosedNotComplete() throws Exception {
        StorageService.StoredFile stored =
                storage.store("attachments", "pixel.png", "image/png", PNG);

        try (StorageService.StreamHandle handle = storage.open(stored.storageKey())) {
            assertThat(handle.content().readNBytes(4)).hasSize(4);
        }

        assertThat(streamDurationCount(StorageMetricsAspect.OPEN, "CLOSED")).isEqualTo(1);
        assertThat(streamDurationCount(StorageMetricsAspect.OPEN, "COMPLETE")).isZero();
        assertThat(streamBytes(StorageMetricsAspect.OPEN, "CLOSED")).isEqualTo(4);
    }

    @Test
    void rangeOpenIsCountedSeparatelyFromWholeOpen() throws Exception {
        StorageService.StoredFile stored =
                storage.store("attachments", "pixel.png", "image/png", PNG);

        try (StorageService.StreamHandle handle =
                     storage.openRange(stored.storageKey(), 2, 3)) {
            assertThat(handle.content().readAllBytes()).hasSize(3);
        }

        assertThat(operationCount(StorageMetricsAspect.OPEN_RANGE, "SUCCESS")).isEqualTo(1);
        assertThat(streamDurationCount(StorageMetricsAspect.OPEN_RANGE, "COMPLETE"))
                .isEqualTo(1);
        assertThat(streamBytes(StorageMetricsAspect.OPEN_RANGE, "COMPLETE")).isEqualTo(3);
    }

    @Test
    void storageMetersOnlyEmitBoundedTagKeysAndValues() throws Exception {
        StorageService.StoredFile stored =
                storage.store("attachments", "pixel.png", "image/png", PNG);
        try (StorageService.StreamHandle handle = storage.open(stored.storageKey())) {
            handle.content().readAllBytes();
        }
        try (StorageService.StreamHandle handle =
                     storage.openRange(stored.storageKey(), 0, 2)) {
            handle.content().readAllBytes();
        }
        storage.deleteIfExists(stored.storageKey());

        Set<String> keys = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("nexachat.storage."))
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(Tag::getKey)
                .collect(Collectors.toSet());
        assertThat(keys).containsExactlyInAnyOrder("operation", "provider", "outcome");

        assertThat(tagValues("operation")).isSubsetOf(
                StorageMetricsAspect.STORE, StorageMetricsAspect.OPEN,
                StorageMetricsAspect.OPEN_RANGE, StorageMetricsAspect.DELETE,
                ObservabilityMetrics.UNRECORDED);
        assertThat(tagValues("provider"))
                .isSubsetOf(StorageMetricsAspect.LOCAL, ObservabilityMetrics.UNRECORDED)
                .doesNotContain(StorageMetricsAspect.S3);
        assertThat(tagValues("outcome")).isSubsetOf(
                "SUCCESS", "FAILURE", "COMPLETE", "CLOSED", ObservabilityMetrics.UNRECORDED);
    }

    private double operationCount(String operation, String outcome) {
        var counter = registry.find(ObservabilityMetrics.STORAGE_OPERATIONS)
                .tag("operation", operation)
                .tag("provider", StorageMetricsAspect.LOCAL)
                .tag("outcome", outcome)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    private double operationDurationCount(String operation, String outcome) {
        var timer = registry.find(ObservabilityMetrics.STORAGE_OPERATION_DURATION)
                .tag("operation", operation)
                .tag("provider", StorageMetricsAspect.LOCAL)
                .tag("outcome", outcome)
                .timer();
        return timer == null ? 0.0 : timer.count();
    }

    private double operationBytes(String operation, String outcome) {
        var summary = registry.find(ObservabilityMetrics.STORAGE_OPERATION_BYTES)
                .tag("operation", operation)
                .tag("provider", StorageMetricsAspect.LOCAL)
                .tag("outcome", outcome)
                .summary();
        return summary == null ? 0.0 : summary.totalAmount();
    }

    private double streamDurationCount(String operation, String outcome) {
        var timer = registry.find(ObservabilityMetrics.STORAGE_STREAM_DURATION)
                .tag("operation", operation)
                .tag("provider", StorageMetricsAspect.LOCAL)
                .tag("outcome", outcome)
                .timer();
        return timer == null ? 0.0 : timer.count();
    }

    private double streamBytes(String operation, String outcome) {
        var summary = registry.find(ObservabilityMetrics.STORAGE_STREAM_BYTES)
                .tag("operation", operation)
                .tag("provider", StorageMetricsAspect.LOCAL)
                .tag("outcome", outcome)
                .summary();
        return summary == null ? 0.0 : summary.totalAmount();
    }

    private Set<String> tagValues(String key) {
        return registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().startsWith("nexachat.storage."))
                .map(meter -> meter.getId().getTag(key))
                .filter(value -> value != null)
                .collect(Collectors.toSet());
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
