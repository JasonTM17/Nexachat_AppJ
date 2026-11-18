package com.nexachat.streak;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * The streak engine, free of Spring and persistence so every rule is unit
 * testable.
 *
 * <p>A chain has two sides, A and B. For a direct conversation they are the
 * two members; for the app-wide check-in streak A is "opened the app" and B is
 * "sent something". Each side remembers the last streak day it was recorded
 * on. When both sides stand on the same day that is newer than the chain's
 * {@code lastDay}, the day completes and the chain advances:
 *
 * <ul>
 *   <li>no previous day, or a gap of three or more days: the chain restarts at 1;</li>
 *   <li>a gap of one day (consecutive): the count grows by one;</li>
 *   <li>a gap of two days (exactly one missed day): the count still grows by
 *       one when the grace day is available, and the missed day is recorded in
 *       {@code freezeUsedOn}; otherwise the chain restarts at 1.</li>
 * </ul>
 *
 * <p>The grace day is available when no grace was consumed in the six days
 * before the completing day. Days never move backwards: a record older than
 * what is already stored changes nothing, which makes replays idempotent.
 */
public final class StreakRules {

    /** Counts at which a milestone is reached, ascending. */
    public static final int[] MILESTONES = {3, 7, 30, 100};

    /** A grace day cannot be reused within this many days of the previous one. */
    static final int FREEZE_COOLDOWN_DAYS = 6;

    /** Which half of the chain a record applies to. */
    public enum Side { A, B }

    /** Persistent chain state; {@code null} dates mean "never". */
    public record State(LocalDate dayA, LocalDate dayB, int count, int bestCount,
                        LocalDate lastDay, LocalDate startedOn, LocalDate freezeUsedOn) {

        public static final State EMPTY = new State(null, null, 0, 0, null, null, null);
    }

    /** Read-side projection of a {@link State} against a given day. */
    public record View(int count, int bestCount, LocalDate startedOn, LocalDate lastDay,
                       LocalDate day, boolean atRisk, boolean freezeAvailable, int milestone,
                       boolean sideADoneToday, boolean sideBDoneToday) {
    }

    private StreakRules() {
    }

    /**
     * Records that {@code side} was active on {@code day} and returns the new
     * state, or the same instance when nothing changed.
     */
    public static State record(State state, Side side, LocalDate day) {
        LocalDate dayA = state.dayA();
        LocalDate dayB = state.dayB();
        if (side == Side.A) {
            dayA = later(dayA, day);
        } else {
            dayB = later(dayB, day);
        }
        State next = new State(dayA, dayB, state.count(), state.bestCount(), state.lastDay(),
                state.startedOn(), state.freezeUsedOn());
        boolean completesDay = day.equals(dayA) && day.equals(dayB)
                && (state.lastDay() == null || state.lastDay().isBefore(day));
        if (completesDay) {
            next = advance(next, day);
        }
        return next.equals(state) ? state : next;
    }

    private static State advance(State state, LocalDate day) {
        int count;
        LocalDate startedOn = state.startedOn();
        LocalDate freezeUsedOn = state.freezeUsedOn();
        if (state.lastDay() == null) {
            count = 1;
            startedOn = day;
        } else {
            long gap = ChronoUnit.DAYS.between(state.lastDay(), day);
            if (gap == 1) {
                count = state.count() + 1;
            } else if (gap == 2 && freezeAvailable(freezeUsedOn, day)) {
                count = state.count() + 1;
                freezeUsedOn = state.lastDay().plusDays(1);
            } else {
                count = 1;
                startedOn = day;
            }
        }
        return new State(state.dayA(), state.dayB(), count, Math.max(state.bestCount(), count),
                day, startedOn, freezeUsedOn);
    }

    /** Whether a grace day could be consumed to bridge a one-day miss ending on {@code day}. */
    public static boolean freezeAvailable(LocalDate freezeUsedOn, LocalDate day) {
        return freezeUsedOn == null || freezeUsedOn.isBefore(day.minusDays(FREEZE_COOLDOWN_DAYS));
    }

    /**
     * Projects the stored state onto {@code today}: expired chains read as
     * zero. A grace day is only reported while the chain is alive — there is
     * nothing to bridge once it has lapsed, and a chat without a chain has no
     * grace day at all.
     */
    public static View view(State state, LocalDate today) {
        boolean alive = isAlive(state, today);
        int count = alive ? state.count() : 0;
        boolean atRisk = alive && state.lastDay() != null && state.lastDay().isBefore(today);
        return new View(count, state.bestCount(), alive ? state.startedOn() : null, state.lastDay(),
                today, atRisk, alive && freezeAvailable(state.freezeUsedOn(), today), milestone(count),
                today.equals(state.dayA()), today.equals(state.dayB()));
    }

    /** A chain is alive while it can still be extended without restarting. */
    public static boolean isAlive(State state, LocalDate today) {
        if (state.count() <= 0 || state.lastDay() == null) {
            return false;
        }
        long gap = ChronoUnit.DAYS.between(state.lastDay(), today);
        return gap <= 1 || (gap == 2 && freezeAvailable(state.freezeUsedOn(), today));
    }

    /** Highest milestone reached by {@code count}, or 0. */
    public static int milestone(int count) {
        int reached = 0;
        for (int milestone : MILESTONES) {
            if (count >= milestone) {
                reached = milestone;
            }
        }
        return reached;
    }

    private static LocalDate later(LocalDate current, LocalDate candidate) {
        return current != null && !candidate.isAfter(current) ? current : candidate;
    }
}
