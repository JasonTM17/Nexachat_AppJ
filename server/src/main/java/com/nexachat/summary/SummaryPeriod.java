package com.nexachat.summary;

import java.time.Instant;
import java.time.LocalDate;

/**
 * The local calendar day a {@code TODAY} summary covers: {@code [start, end)} in the
 * caller's zone. {@code timeZoneDefaulted} is true when the client sent no zone.
 */
public record SummaryPeriod(
        LocalDate date,
        String timeZone,
        Instant start,
        Instant end,
        boolean timeZoneDefaulted) {
}
