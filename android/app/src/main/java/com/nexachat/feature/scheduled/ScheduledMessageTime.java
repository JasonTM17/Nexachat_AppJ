package com.nexachat.feature.scheduled;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/** Pure UTC/local conversions and validation bounds for scheduled sends. */
public final class ScheduledMessageTime {

    /** Server rule: a scheduled send must sit at least one minute in the future. */
    public static final Duration MIN_LEAD_TIME = Duration.ofSeconds(60);

    private ScheduledMessageTime() { }

    /**
     * Combines a Material date picker day (UTC midnight millis) with a local
     * hour/minute in the device zone.
     */
    @NonNull
    public static Instant combine(long utcDayMillis, int hour, int minute, @NonNull ZoneId zone) {
        Objects.requireNonNull(zone, "zone");
        LocalDate date = Instant.ofEpochMilli(utcDayMillis)
                .atZone(ZoneOffset.UTC)
                .toLocalDate();
        return LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant();
    }

    /** The date picker value that represents the local calendar day of an instant. */
    public static long pickerDayMillis(@NonNull Instant instant, @NonNull ZoneId zone) {
        Objects.requireNonNull(instant, "instant");
        Objects.requireNonNull(zone, "zone");
        return instant.atZone(zone).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli();
    }

    /** Server payload format: second precision, UTC, trailing Z. */
    @NonNull
    public static String toUtcIso(@NonNull Instant instant) {
        return Objects.requireNonNull(instant, "instant")
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    }

    @Nullable
    public static Instant parse(@Nullable String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso.trim());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /**
     * Client-side sanity check matching the server floor: the send time must sit
     * at least {@link #MIN_LEAD_TIME} ahead of now. The server may still clamp a
     * borderline value; the echoed {@code sendAt} is what the UI displays.
     */
    public static boolean isWithinLeadTime(@Nullable Instant sendAt, @NonNull Clock clock) {
        if (sendAt == null) {
            return false;
        }
        return !sendAt.isBefore(clock.instant().plus(MIN_LEAD_TIME));
    }
}
