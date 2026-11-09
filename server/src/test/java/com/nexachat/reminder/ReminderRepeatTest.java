package com.nexachat.reminder;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repeat-advance math. All expectations are explicit UTC instants so the
 * month-end clamp, the strictly-after rule and the absence of DST shifts are
 * visible in the test itself.
 */
class ReminderRepeatTest {

    private static final Instant ANCHOR = Instant.parse("2026-01-31T09:00:00Z");

    @Test
    void noneHasNoNextOccurrence() {
        assertThat(ReminderRepeat.NONE.nextAfter(ANCHOR, ANCHOR.plusSeconds(60)))
                .isEmpty();
    }

    @Test
    void anAnchorStillInTheFutureIsTheFirstOccurrence() {
        Instant after = Instant.parse("2026-01-30T09:00:00Z");
        assertThat(ReminderRepeat.DAILY.nextAfter(ANCHOR, after)).contains(ANCHOR);
        assertThat(ReminderRepeat.MONTHLY.nextAfter(ANCHOR, after)).contains(ANCHOR);
    }

    @Test
    void dailyAdvancesToTheNextUtcDay() {
        Instant after = Instant.parse("2026-03-10T08:00:00Z");
        assertThat(ReminderRepeat.DAILY.nextAfter(Instant.parse("2026-03-07T09:00:00Z"), after))
                .contains(Instant.parse("2026-03-10T09:00:00Z"));
    }

    @Test
    void anExactOccurrenceIsSkippedToTheFollowingOne() {
        assertThat(ReminderRepeat.DAILY.nextAfter(ANCHOR, ANCHOR))
                .contains(Instant.parse("2026-02-01T09:00:00Z"));
        assertThat(ReminderRepeat.WEEKLY.nextAfter(ANCHOR, ANCHOR))
                .contains(Instant.parse("2026-02-07T09:00:00Z"));
        assertThat(ReminderRepeat.MONTHLY.nextAfter(ANCHOR, ANCHOR))
                .contains(Instant.parse("2026-02-28T09:00:00Z"));
    }

    @Test
    void weeklyAddsSevenUtcDays() {
        Instant anchor = Instant.parse("2026-03-02T09:00:00Z");
        Instant after = Instant.parse("2026-03-04T12:00:00Z");
        assertThat(ReminderRepeat.WEEKLY.nextAfter(anchor, after))
                .contains(Instant.parse("2026-03-09T09:00:00Z"));
    }

    @Test
    void overdueDailyCatchUpReturnsOnlyTheFirstFutureOccurrence() {
        Instant anchor = Instant.parse("2026-01-01T09:00:00Z");
        Instant after = Instant.parse("2026-03-15T10:00:00Z");
        assertThat(ReminderRepeat.DAILY.nextAfter(anchor, after))
                .contains(Instant.parse("2026-03-16T09:00:00Z"));
    }

    @Test
    void monthlyClampsToMonthEndWithoutDrifting() {
        assertThat(ReminderRepeat.MONTHLY.nextAfter(ANCHOR, Instant.parse("2026-01-31T09:00:00Z")))
                .contains(Instant.parse("2026-02-28T09:00:00Z"));
        assertThat(ReminderRepeat.MONTHLY.nextAfter(ANCHOR, Instant.parse("2026-02-28T09:00:00Z")))
                .contains(Instant.parse("2026-03-31T09:00:00Z"));
        assertThat(ReminderRepeat.MONTHLY.nextAfter(ANCHOR, Instant.parse("2026-03-01T00:00:00Z")))
                .contains(Instant.parse("2026-03-31T09:00:00Z"));
        assertThat(ReminderRepeat.MONTHLY.nextAfter(ANCHOR, Instant.parse("2026-03-31T09:00:00Z")))
                .contains(Instant.parse("2026-04-30T09:00:00Z"));
        assertThat(ReminderRepeat.MONTHLY.nextAfter(ANCHOR, Instant.parse("2026-05-01T00:00:00Z")))
                .contains(Instant.parse("2026-05-31T09:00:00Z"));
    }

    @Test
    void monthlyHandlesLeapFebruary() {
        Instant anchor = Instant.parse("2028-01-31T09:00:00Z");
        assertThat(ReminderRepeat.MONTHLY.nextAfter(anchor, Instant.parse("2028-02-01T00:00:00Z")))
                .contains(Instant.parse("2028-02-29T09:00:00Z"));
        assertThat(ReminderRepeat.MONTHLY.nextAfter(anchor, Instant.parse("2028-02-29T09:00:00Z")))
                .contains(Instant.parse("2028-03-31T09:00:00Z"));
    }

    @Test
    void utcArithmeticDoesNotShiftAcrossSeasonalClockChanges() {
        // 8 March 2026 is the US DST switch; UTC arithmetic must keep 07:30Z.
        Instant dailyAnchor = Instant.parse("2026-03-07T07:30:00Z");
        assertThat(ReminderRepeat.DAILY.nextAfter(
                dailyAnchor, Instant.parse("2026-03-08T12:00:00Z")))
                .contains(Instant.parse("2026-03-09T07:30:00Z"));

        // 25 October 2026 is the European DST switch; November keeps 01:30Z.
        Instant monthlyAnchor = Instant.parse("2026-10-25T01:30:00Z");
        assertThat(ReminderRepeat.MONTHLY.nextAfter(
                monthlyAnchor, Instant.parse("2026-10-25T02:00:00Z")))
                .contains(Instant.parse("2026-11-25T01:30:00Z"));
    }

    @Test
    void dailyAnchoredOnMonthEndKeepsTheTimeOfDay() {
        Instant lateAnchor = Instant.parse("2026-01-31T23:30:00Z");
        assertThat(ReminderRepeat.DAILY.nextAfter(
                lateAnchor, Instant.parse("2026-02-01T00:00:00Z")))
                .contains(Instant.parse("2026-02-01T23:30:00Z"));
    }
}
