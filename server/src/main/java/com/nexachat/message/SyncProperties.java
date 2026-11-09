package com.nexachat.message;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Delta-sync bounds. {@code maxReactionUpdates} caps how many reaction events
 * are scanned per conversation in one delta request (and therefore caps the
 * number of reaction-update payloads, which is at most one per event);
 * {@code reactionRetentionDays} is how long reaction events are kept before
 * the scheduled purge removes them.
 */
@Component
@ConfigurationProperties(prefix = "nexachat.sync")
@Validated
public class SyncProperties {

    /** Default 200 — matches the delta payload ceiling for one conversation. */
    private int maxReactionUpdates = 200;

    /** Reaction events older than this are purged and cannot serve a delta. */
    @Min(1)
    @Max(3_650)
    private int reactionRetentionDays = 30;

    public int getMaxReactionUpdates() {
        return maxReactionUpdates;
    }

    public void setMaxReactionUpdates(int maxReactionUpdates) {
        this.maxReactionUpdates = maxReactionUpdates;
    }

    public int getReactionRetentionDays() {
        return reactionRetentionDays;
    }

    public void setReactionRetentionDays(int reactionRetentionDays) {
        this.reactionRetentionDays = reactionRetentionDays;
    }
}
