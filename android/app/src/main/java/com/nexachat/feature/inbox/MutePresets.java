package com.nexachat.feature.inbox;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;

/**
 * Client-side Zalo-style mute presets. The server stores only the resulting
 * instant (or the forever flag); preset labels never leave the device.
 */
public final class MutePresets {

    public static final Duration ONE_HOUR = Duration.ofHours(1);
    public static final Duration FOUR_HOURS = Duration.ofHours(4);
    public static final LocalTime EIGHT_AM = LocalTime.of(8, 0);

    private MutePresets() {}

    /** Mutes for exactly one hour from {@code now}. */
    public static Instant oneHour(Instant now) {
        return Objects.requireNonNull(now, "now").plus(ONE_HOUR);
    }

    /** Mutes for exactly four hours from {@code now}. */
    public static Instant fourHours(Instant now) {
        return Objects.requireNonNull(now, "now").plus(FOUR_HOURS);
    }

    /**
     * The next local 08:00, strictly after {@code now}. Before 08:00 it is
     * today's 08:00; at or after 08:00 it is tomorrow's, which keeps the
     * resulting instant strictly in the future and inside the server's
     * 365-day bound.
     */
    public static Instant untilNextEightAm(Instant now, ZoneId zone) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(zone, "zone");
        ZonedDateTime local = now.atZone(zone);
        ZonedDateTime candidate = local.toLocalDate().atTime(EIGHT_AM).atZone(zone);
        if (!candidate.toInstant().isAfter(now)) {
            candidate = candidate.plusDays(1);
        }
        return candidate.toInstant();
    }
}
