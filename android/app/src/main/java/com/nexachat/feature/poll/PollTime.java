package com.nexachat.feature.poll;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/** Pure date conversions for the optional poll expiry picker. */
public final class PollTime {

    private PollTime() { }

    /** A picked UTC day becomes the end of that local day, inclusive of its last second. */
    @NonNull
    public static Instant endOfDay(long utcDayMillis, @NonNull ZoneId zone) {
        LocalDate date = Instant.ofEpochMilli(utcDayMillis)
                .atZone(ZoneOffset.UTC)
                .toLocalDate();
        return date.atTime(23, 59, 59).atZone(zone).toInstant();
    }

    public static long pickerDayMillis(@NonNull Instant instant, @NonNull ZoneId zone) {
        return instant.atZone(zone).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli();
    }

    @NonNull
    public static String toUtcIso(@NonNull Instant instant) {
        return instant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    }

    /**
     * The last selectable UTC day whose local end-of-day still fits inside the
     * server expiry window, so the picker never offers a date the server rejects.
     */
    public static long maxExpiryDayMillis(
            @NonNull Instant now, @NonNull ZoneId zone, int days) {
        Instant limit = now.plus(java.time.Duration.ofDays(days));
        LocalDate date = limit.atZone(zone).toLocalDate();
        if (date.atTime(23, 59, 59).atZone(zone).toInstant().isAfter(limit)) {
            date = date.minusDays(1);
        }
        return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
    }

    @Nullable
    public static java.time.Instant parse(@Nullable String iso) {
        if (iso == null || iso.trim().isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(iso.trim());
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
