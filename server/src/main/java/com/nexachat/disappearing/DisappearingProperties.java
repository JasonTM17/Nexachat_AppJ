package com.nexachat.disappearing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Controls for the disappearing-message purge sweep. The job is enabled by
 * default — expiry is a correctness feature, not an optional cleanup — and
 * runs on a coarse, configurable cadence in bounded batches.
 */
@Component
@ConfigurationProperties(prefix = "nexachat.disappearing")
public class DisappearingProperties {

    public static final int DEFAULT_BATCH_SIZE = 200;
    public static final int MAX_BATCH_SIZE = 1_000;
    public static final long DEFAULT_PURGE_INTERVAL_MS = 60_000;
    public static final long MIN_PURGE_INTERVAL_MS = 5_000;

    private boolean enabled = true;
    private int batchSize = DEFAULT_BATCH_SIZE;
    private long purgeIntervalMs = DEFAULT_PURGE_INTERVAL_MS;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "nexachat.disappearing.batch-size must be between 1 and " + MAX_BATCH_SIZE);
        }
        this.batchSize = batchSize;
    }

    public long getPurgeIntervalMs() {
        return purgeIntervalMs;
    }

    public void setPurgeIntervalMs(long purgeIntervalMs) {
        if (purgeIntervalMs < MIN_PURGE_INTERVAL_MS) {
            throw new IllegalArgumentException(
                    "nexachat.disappearing.purge-interval-ms must be at least "
                            + MIN_PURGE_INTERVAL_MS);
        }
        this.purgeIntervalMs = purgeIntervalMs;
    }
}
