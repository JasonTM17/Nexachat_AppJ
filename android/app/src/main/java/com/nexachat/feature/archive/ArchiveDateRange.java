package com.nexachat.feature.archive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/** Converts Material date-picker UTC day values to inclusive ISO-8601 query bounds. */
public final class ArchiveDateRange {

    private ArchiveDateRange() { }

    @NonNull
    public static String startOfDay(long pickerMillis) {
        LocalDate date = Instant.ofEpochMilli(pickerMillis)
                .atZone(ZoneOffset.UTC)
                .toLocalDate();
        return date.atStartOfDay(ZoneOffset.UTC).toInstant().toString();
    }

    @NonNull
    public static String endOfDay(long pickerMillis) {
        LocalDate date = Instant.ofEpochMilli(pickerMillis)
                .atZone(ZoneOffset.UTC)
                .toLocalDate();
        return date.plusDays(1).atStartOfDay(ZoneOffset.UTC).minusNanos(1).toInstant().toString();
    }

    @Nullable
    public static Long toPickerMillis(@Nullable String iso) {
        if (iso == null) {
            return null;
        }
        try {
            LocalDate date = Instant.parse(iso).atZone(ZoneOffset.UTC).toLocalDate();
            return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
