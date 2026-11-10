package com.nexachat.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the optional refresh-response recovery key and cleanup. */
@ConfigurationProperties(prefix = "nexachat.auth.refresh-replay")
public class RefreshReplayProperties {

    /** Base64 encoding of exactly 32 bytes. Empty disables replay recovery but keeps strict rotation available. */
    private String key = "";

    private int cleanupBatchSize = 500;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public int getCleanupBatchSize() {
        return cleanupBatchSize;
    }

    public void setCleanupBatchSize(int cleanupBatchSize) {
        this.cleanupBatchSize = cleanupBatchSize;
    }
}
