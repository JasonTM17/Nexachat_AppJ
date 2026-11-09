package com.nexachat.reminder;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * Repeat rule for a conversation reminder.
 *
 * <p>All arithmetic is UTC instant arithmetic, so there is no DST shift: a
 * daily reminder keeps the same UTC wall time all year. Monthly occurrences
 * are always derived from the immutable anchor ({@code anchor_at}), not from
 * the previous (possibly clamped) occurrence; this is what keeps 31 January
 * on the 31st instead of drifting to the 28th after February.</p>
 */
public enum ReminderRepeat {

    NONE,
    DAILY,
    WEEKLY,
    MONTHLY;

    /**
     * The first occurrence strictly after {@code after}, or empty for
     * {@link #NONE}.
     *
     * <p>When a repeating reminder is overdue (for example after server
     * downtime), this returns the first FUTURE occurrence, not every missed
     * occurrence. Callers therefore send one catch-up push and then resume the
     * normal cadence.</p>
     *
     * @param anchor the original first occurrence
     * @param after  the instant that must be strictly before the result
     */
    public Optional<Instant> nextAfter(Instant anchor, Instant after) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(after, "after");
        return switch (this) {
            case NONE -> Optional.empty();
            case DAILY -> Optional.of(nextFixedDays(anchor, after, 1L));
            case WEEKLY -> Optional.of(nextFixedDays(anchor, after, 7L));
            case MONTHLY -> Optional.of(nextMonthly(anchor, after));
        };
    }

    /** Fixed-width UTC periods; {@link ChronoUnit#DAYS} is exact on Instant. */
    private static Instant nextFixedDays(Instant anchor, Instant after, long periodDays) {
        if (after.isBefore(anchor)) {
            return anchor;
        }
        long days = ChronoUnit.DAYS.between(anchor, after);
        long periods = Math.max(0L, days / periodDays);
        Instant candidate = anchor.plus(periods * periodDays, ChronoUnit.DAYS);
        while (!candidate.isAfter(after)) {
            periods++;
            candidate = anchor.plus(periods * periodDays, ChronoUnit.DAYS);
        }
        return candidate;
    }

    private static Instant nextMonthly(Instant anchor, Instant after) {
        if (after.isBefore(anchor)) {
            return anchor;
        }
        ZonedDateTime anchorUtc = anchor.atZone(ZoneOffset.UTC);
        ZonedDateTime afterUtc = after.atZone(ZoneOffset.UTC);
        long months = Math.max(0L, ChronoUnit.MONTHS.between(anchorUtc, afterUtc));
        ZonedDateTime candidate = anchorUtc.plusMonths(months);
        while (!candidate.toInstant().isAfter(after)) {
            months++;
            candidate = anchorUtc.plusMonths(months);
        }
        return candidate.toInstant();
    }
}
