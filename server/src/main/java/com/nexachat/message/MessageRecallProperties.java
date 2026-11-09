package com.nexachat.message;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Configuration and boundary rules for author message recall. */
@Component
@ConfigurationProperties(prefix = "nexachat.message")
public class MessageRecallProperties {

    public static final Duration DEFAULT_RECALL_WINDOW = Duration.ofHours(24);
    public static final Duration MIN_RECALL_WINDOW = Duration.ofMinutes(1);
    public static final Duration MAX_RECALL_WINDOW = Duration.ofDays(7);
    public static final boolean GROUP_ADMIN_MAY_RECALL_AFTER_WINDOW = true;

    private Duration recallWindow = DEFAULT_RECALL_WINDOW;

    public Duration getRecallWindow() {
        return recallWindow;
    }

    public void setRecallWindow(Duration recallWindow) {
        if (recallWindow == null
                || recallWindow.compareTo(MIN_RECALL_WINDOW) < 0
                || recallWindow.compareTo(MAX_RECALL_WINDOW) > 0) {
            throw new IllegalArgumentException(
                    "nexachat.message.recall-window must be between PT1M and P7D");
        }
        this.recallWindow = recallWindow;
    }

    /** The configured boundary is inclusive; only a later instant has expired. */
    public boolean isWithinRecallWindow(Instant createdAt, Instant now) {
        return !now.isAfter(createdAt.plus(recallWindow));
    }
}
