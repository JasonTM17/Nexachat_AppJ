package com.nexachat.feature.reminder;

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

/** Pure UTC/local conversions and validation bounds for reminder scheduling. */
public final class ReminderTime {

    public static final Duration MAX_LEAD_TIME = Duration.ofDays(365);

    private ReminderTime() { }

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
        return Objects.requireNonNull(instant, "instant").truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
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

    /** Server rule: strictly future and at most 365 days ahead. */
    public static boolean isFutureWithinYear(@Nullable Instant remindAt, @NonNull Clock clock) {
        if (remindAt == null) {
            return false;
        }
        Instant now = clock.instant();
        return remindAt.isAfter(now) && !remindAt.isAfter(now.plus(MAX_LEAD_TIME));
    }
}
