package com.nexachat.feature.feed;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/** Turns the server's ISO-8601 instants into the epoch millis the list renders. */
public final class FeedTimeRules {

    /** Instants this young (or slightly ahead, on clock skew) read as "just now". */
    static final long JUST_NOW_WINDOW_MS = 60_000L;

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

    /**
     * Whether the instant reads as "just now": under a minute old or in the
     * future (clock skew between server and device). At exactly a minute the
     * relative label kicks in.
     */
    public static boolean isJustNow(long epochMs, long nowMs) {
        return epochMs > 0L && nowMs - epochMs < JUST_NOW_WINDOW_MS;
    }
}
