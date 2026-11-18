package com.nexachat.summary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SummaryPeriodsTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void missingZoneDefaultsToVietnamAndIsFlagged(String raw) {
        SummaryPeriod period = SummaryPeriods.today(Instant.parse("2026-11-17T05:00:00Z"), raw);

        assertThat(period.timeZone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(period.timeZoneDefaulted()).isTrue();
        assertThat(period.date()).isEqualTo(LocalDate.parse("2026-11-17"));
        assertThat(period.start()).isEqualTo(Instant.parse("2026-11-16T17:00:00Z"));
        assertThat(period.end()).isEqualTo(Instant.parse("2026-11-17T17:00:00Z"));
    }

    @Test
    void vietnamDayDiffersFromUtcDayLateInTheUtcEvening() {
        // 18:00 UTC on the 16th is already 01:00 on the 17th in Vietnam.
        Instant now = Instant.parse("2026-11-16T18:00:00Z");

        SummaryPeriod vietnam = SummaryPeriods.today(now, null);
        SummaryPeriod utc = SummaryPeriods.today(now, "UTC");

        assertThat(vietnam.date()).isEqualTo(LocalDate.parse("2026-11-17"));
        assertThat(utc.date()).isEqualTo(LocalDate.parse("2026-11-16"));
        assertThat(utc.timeZoneDefaulted()).isFalse();
    }

    @Test
    void startOfLocalDayIsInsideAndStartOfNextDayIsOutside() {
        SummaryPeriod period = SummaryPeriods.today(Instant.parse("2026-11-17T10:00:00Z"), "Asia/Ho_Chi_Minh");

        assertThat(contains(period, Instant.parse("2026-11-16T17:00:00Z"))).isTrue();
        assertThat(contains(period, Instant.parse("2026-11-16T16:59:59.999999Z"))).isFalse();
        assertThat(contains(period, Instant.parse("2026-11-17T16:59:59.999999Z"))).isTrue();
        assertThat(contains(period, Instant.parse("2026-11-17T17:00:00Z"))).isFalse();
    }

    @Test
    void fallBackDayLastsTwentyFiveHours() {
        SummaryPeriod period = SummaryPeriods.today(Instant.parse("2026-11-01T12:00:00Z"), "America/New_York");

        assertThat(period.start()).isEqualTo(Instant.parse("2026-11-01T04:00:00Z"));
        assertThat(period.end()).isEqualTo(Instant.parse("2026-11-02T05:00:00Z"));
        assertThat(Duration.between(period.start(), period.end())).isEqualTo(Duration.ofHours(25));
    }

    @Test
    void springForwardDayLastsTwentyThreeHours() {
        SummaryPeriod period = SummaryPeriods.today(Instant.parse("2026-03-08T18:00:00Z"), "America/New_York");

        assertThat(period.start()).isEqualTo(Instant.parse("2026-03-08T05:00:00Z"));
        assertThat(period.end()).isEqualTo(Instant.parse("2026-03-09T04:00:00Z"));
        assertThat(Duration.between(period.start(), period.end())).isEqualTo(Duration.ofHours(23));
    }

    @Test
    void skippedMidnightStartsAtTheFirstValidInstantOfTheDay() {
        // Sao Paulo skipped 00:00-01:00 on 2018-11-04; the day starts at 01:00 local (03:00 UTC).
        SummaryPeriod period = SummaryPeriods.today(Instant.parse("2018-11-04T15:00:00Z"), "America/Sao_Paulo");

        assertThat(period.date()).isEqualTo(LocalDate.parse("2018-11-04"));
        assertThat(period.start()).isEqualTo(Instant.parse("2018-11-04T03:00:00Z"));
        assertThat(period.end()).isEqualTo(Instant.parse("2018-11-05T02:00:00Z"));
    }

    @Test
    void offsetAndOddZonesResolve() {
        assertThat(SummaryPeriods.today(Instant.parse("2026-11-17T20:00:00Z"), "+07:00").date())
                .isEqualTo(LocalDate.parse("2026-11-18"));
        assertThat(SummaryPeriods.today(Instant.parse("2026-11-17T10:00:00Z"), "Asia/Kathmandu").start())
                .isEqualTo(Instant.parse("2026-11-16T18:15:00Z"));
        assertThat(SummaryPeriods.today(Instant.parse("2026-11-17T10:00:00Z"), "Pacific/Kiritimati").date())
                .isEqualTo(LocalDate.parse("2026-11-18"));
    }

    @Test
    void berlinSpringForwardDayLastsTwentyThreeHours() {
        SummaryPeriod period = SummaryPeriods.today(Instant.parse("2026-03-29T12:00:00Z"), "Europe/Berlin");

        assertThat(period.start()).isEqualTo(Instant.parse("2026-03-28T23:00:00Z"));
        assertThat(period.end()).isEqualTo(Instant.parse("2026-03-29T22:00:00Z"));
        assertThat(Duration.between(period.start(), period.end())).isEqualTo(Duration.ofHours(23));
    }

    @Test
    void berlinFallBackDayLastsTwentyFiveHours() {
        SummaryPeriod period = SummaryPeriods.today(Instant.parse("2026-10-25T12:00:00Z"), "Europe/Berlin");

        assertThat(period.start()).isEqualTo(Instant.parse("2026-10-24T22:00:00Z"));
        assertThat(period.end()).isEqualTo(Instant.parse("2026-10-25T23:00:00Z"));
        assertThat(Duration.between(period.start(), period.end())).isEqualTo(Duration.ofHours(25));
    }

    @Test
    void lordHoweShiftsItsDayLengthByThirtyMinutesOnBothTransitionDays() {
        // Lord Howe moves only half an hour: the end-of-season day gains 30 minutes
        // and the start-of-season day loses 30 minutes.
        SummaryPeriod dstEnd = SummaryPeriods.today(Instant.parse("2026-04-05T05:00:00Z"), "Australia/Lord_Howe");
        assertThat(dstEnd.start()).isEqualTo(Instant.parse("2026-04-04T13:00:00Z"));
        assertThat(dstEnd.end()).isEqualTo(Instant.parse("2026-04-05T13:30:00Z"));
        assertThat(Duration.between(dstEnd.start(), dstEnd.end())).isEqualTo(Duration.ofMinutes(1470));

        SummaryPeriod dstStart = SummaryPeriods.today(Instant.parse("2026-10-04T05:00:00Z"), "Australia/Lord_Howe");
        assertThat(dstStart.start()).isEqualTo(Instant.parse("2026-10-03T13:30:00Z"));
        assertThat(dstStart.end()).isEqualTo(Instant.parse("2026-10-04T13:00:00Z"));
        assertThat(Duration.between(dstStart.start(), dstStart.end())).isEqualTo(Duration.ofMinutes(1410));
    }

    @Test
    void apiaSkippedDayKeepsTheCalendarContinuousWithoutThrowing() {
        // Samoa skipped 2011-12-30 entirely. Requests from either side of the skip
        // resolve their day windows without an error, and both point at the same
        // boundary instant (2011-12-30T10:00:00Z, the local start of the 31st).
        // The local 29th still spans exactly 24 real hours: its midnight-to-midnight
        // window ends at the first valid instant of the 31st.
        SummaryPeriod beforeTheSkip = SummaryPeriods.today(Instant.parse("2011-12-30T09:00:00Z"), "Pacific/Apia");
        assertThat(beforeTheSkip.date()).isEqualTo(LocalDate.parse("2011-12-29"));
        assertThat(beforeTheSkip.start()).isEqualTo(Instant.parse("2011-12-29T10:00:00Z"));
        assertThat(beforeTheSkip.end()).isEqualTo(Instant.parse("2011-12-30T10:00:00Z"));
        assertThat(Duration.between(beforeTheSkip.start(), beforeTheSkip.end())).isEqualTo(Duration.ofHours(24));

        SummaryPeriod afterTheSkip = SummaryPeriods.today(Instant.parse("2011-12-30T12:00:00Z"), "Pacific/Apia");
        assertThat(afterTheSkip.date()).isEqualTo(LocalDate.parse("2011-12-31"));
        assertThat(afterTheSkip.start()).isEqualTo(Instant.parse("2011-12-30T10:00:00Z"));
        assertThat(afterTheSkip.end()).isEqualTo(Instant.parse("2011-12-31T10:00:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Mars/Phobos", "../etc/passwd", "UTC+99", "+25:00", "Asia/Ho_Chi_Minh; DROP TABLE",
            "<script>", "🌏"})
    void garbageZonesAreRejected(String raw) {
        assertThatThrownBy(() -> SummaryPeriods.resolveZone(raw))
                .isInstanceOf(SummaryInvalidTimeZoneException.class);
    }

    @Test
    void overlongZoneIsRejected() {
        assertThatThrownBy(() -> SummaryPeriods.resolveZone("A".repeat(65)))
                .isInstanceOf(SummaryInvalidTimeZoneException.class);
    }

    private static boolean contains(SummaryPeriod period, Instant instant) {
        return !instant.isBefore(period.start()) && instant.isBefore(period.end());
    }
}
