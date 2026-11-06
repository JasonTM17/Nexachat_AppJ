package com.nexachat.data;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/** Parses server event timestamps used to order peer presence updates. */
public final class PresenceTime {

    private PresenceTime() {}

    public static Instant parseEventTime(String serverTs) {
        if (serverTs == null || serverTs.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(serverTs);
        } catch (DateTimeParseException ignored) {
            // Try an explicit offset next.
        }
        try {
            return OffsetDateTime.parse(serverTs).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
