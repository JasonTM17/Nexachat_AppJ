package com.nexachat.attachment;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Operator controls for the orphaned-avatar sweep.
 *
 * <p>The job is opt-in and starts in dry-run mode, matching the retention
 * scheduler. It only ever considers server-generated {@code avatars/} keys
 * older than {@link #getMinAgeHours()}; younger files may still be in the
 * upload-to-reference window, so they are never candidates.</p>
 */
@Component
@Validated
@ConfigurationProperties(prefix = "nexachat.avatar-sweep")
public class AvatarSweepProperties {

    /** Master switch; false means the scheduled task returns immediately. */
    private boolean enabled;

    /** When true, count candidates without deleting files. */
    private boolean dryRun = true;

    /** Maximum files considered per run; 1-500. */
    @Min(1)
    @Max(500)
    private int batchSize = 100;

    /** Minimum file age before deletion; protects in-flight uploads. */
    @Min(1)
    @Max(720)
    private int minAgeHours = 24;

    /** Scheduler delay between runs; 1 minute to 1 day. */
    @Min(60_000)
    @Max(86_400_000)
    private long intervalMs = 86_400_000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMinAgeHours() {
        return minAgeHours;
    }

    public void setMinAgeHours(int minAgeHours) {
        this.minAgeHours = minAgeHours;
    }

    public long getIntervalMs() {
        return intervalMs;
    }

    public void setIntervalMs(long intervalMs) {
        this.intervalMs = intervalMs;
    }
}
