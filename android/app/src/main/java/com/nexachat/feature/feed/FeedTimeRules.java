package com.nexachat.feature.feed;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/** Turns the server's ISO-8601 instants into the epoch millis the list renders. */
public final class FeedTimeRules {

    private FeedTimeRules() { }

    /** Epoch millis of an ISO-8601 instant, or 0 when the value is missing or malformed. */
    public static long epochMillis(@Nullable String isoInstant) {
        if (isoInstant == null || isoInstant.isBlank()) {
            return 0L;
        }
        try {
            return Instant.parse(isoInstant).toEpochMilli();
        } catch (DateTimeParseException malformed) {
            return 0L;
        }
    }
}
