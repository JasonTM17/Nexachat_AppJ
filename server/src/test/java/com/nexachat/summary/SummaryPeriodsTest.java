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
