package com.nexachat.streak;

import com.nexachat.streak.StreakRules.Side;
import com.nexachat.streak.StreakRules.State;
import com.nexachat.streak.StreakRules.View;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class StreakRulesTest {

    private static final LocalDate D1 = LocalDate.of(2026, 11, 1);

    private static LocalDate day(int offset) {
        return D1.plusDays(offset);
    }

    /** Both sides record the same day. */
    private static State both(State state, LocalDate day) {
        return StreakRules.record(StreakRules.record(state, Side.A, day), Side.B, day);
    }

    @Test
    void oneSidedDayDoesNotStartAChain() {
        State state = StreakRules.record(State.EMPTY, Side.A, day(0));

        assertThat(state.count()).isZero();
        assertThat(state.lastDay()).isNull();
        assertThat(state.dayA()).isEqualTo(day(0));
    }

    @Test
    void bothSidesOnOneDayStartAChainOfOne() {
        State state = both(State.EMPTY, day(0));

        assertThat(state.count()).isEqualTo(1);
        assertThat(state.bestCount()).isEqualTo(1);
        assertThat(state.lastDay()).isEqualTo(day(0));
        assertThat(state.startedOn()).isEqualTo(day(0));
    }

    @Test
    void completionOrderOfTheSidesDoesNotMatter() {
        State aFirst = StreakRules.record(StreakRules.record(State.EMPTY, Side.A, day(0)), Side.B, day(0));
        State bFirst = StreakRules.record(StreakRules.record(State.EMPTY, Side.B, day(0)), Side.A, day(0));

        assertThat(aFirst).isEqualTo(bFirst);
    }

    @Test
    void consecutiveDaysGrowTheCount() {
        State state = State.EMPTY;
        for (int i = 0; i < 4; i++) {
            state = both(state, day(i));
        }

        assertThat(state.count()).isEqualTo(4);
        assertThat(state.startedOn()).isEqualTo(day(0));
        assertThat(state.lastDay()).isEqualTo(day(3));
        assertThat(state.freezeUsedOn()).isNull();
    }

    @Test
    void replayingARecordedDayReturnsTheSameState() {
        State state = both(State.EMPTY, day(0));

        assertThat(StreakRules.record(state, Side.A, day(0))).isSameAs(state);
        assertThat(StreakRules.record(state, Side.B, day(0))).isSameAs(state);
    }

    @Test
    void extraMessagesOnTheSameDayNeverCountTwice() {
        State state = both(State.EMPTY, day(0));
        state = StreakRules.record(state, Side.A, day(0));
        state = StreakRules.record(state, Side.B, day(0));

        assertThat(state.count()).isEqualTo(1);
    }

    @Test
    void outOfOrderOlderDaysAreIgnored() {
        State state = both(both(State.EMPTY, day(0)), day(1));

        State replayed = StreakRules.record(state, Side.A, day(0));
        replayed = StreakRules.record(replayed, Side.B, day(0));

        assertThat(replayed).isSameAs(state);
        assertThat(replayed.count()).isEqualTo(2);
    }

    @Test
    void aSideAlreadyAheadDoesNotCompleteAnOlderDay() {
        State state = StreakRules.record(State.EMPTY, Side.A, day(1));
        state = StreakRules.record(state, Side.B, day(0));

        assertThat(state.count()).isZero();
        assertThat(state.dayA()).isEqualTo(day(1));
        assertThat(state.dayB()).isEqualTo(day(0));
    }

    @Test
    void aStaleDayFromACrossMidnightRaceStillCompletes() {
        // Both sides sent on day 1 and day 2, but A's day-2 record was stored
        // first (A crossed midnight while B's day-1 send waited on the
        // conversation lock). B's day-1 record then lands on a side whose
        // stored day is already ahead, so day 1 must still complete.
        State state = both(State.EMPTY, day(0));
        state = StreakRules.record(state, Side.A, day(1));
        state = StreakRules.record(state, Side.A, day(2));
        state = StreakRules.record(state, Side.B, day(1));
        state = StreakRules.record(state, Side.B, day(2));

        assertThat(state.count()).isEqualTo(3);
        assertThat(state.lastDay()).isEqualTo(day(2));
        assertThat(state.freezeUsedOn()).isNull();
    }

    @Test
    void replayingAStaleDayThatAlreadyCompletedChangesNothing() {
        State state = both(State.EMPTY, day(0));
        state = StreakRules.record(state, Side.A, day(1));
        state = StreakRules.record(state, Side.A, day(2));
        state = StreakRules.record(state, Side.B, day(1));
        state = StreakRules.record(state, Side.B, day(2));

        // B's day-1 record already completed its day; a second delayed copy of
        // it must not complete or un-complete anything.
        assertThat(StreakRules.record(state, Side.B, day(1))).isSameAs(state);
        assertThat(StreakRules.record(state, Side.A, day(1))).isSameAs(state);
        assertThat(StreakRules.record(state, Side.A, day(2))).isSameAs(state);
    }

    @Test
    void aStaleDayOlderThanTheGraceWindowNeitherCompletesNorSpendsGrace() {
        // A jumped from day 1 to day 4 (days 2-3 skipped) and B's first record
        // is day 4, so day 4 completes as a restart. B's long-stale day-1
        // record then arrives far outside anything the grace day could
        // bridge: it must not resurrect an old day or spend the grace day.
        State state = both(State.EMPTY, day(0));
        state = StreakRules.record(state, Side.A, day(1));
        state = StreakRules.record(state, Side.A, day(4));
        state = StreakRules.record(state, Side.B, day(4));

        assertThat(state.count()).isEqualTo(1);
        assertThat(state.startedOn()).isEqualTo(day(4));
        assertThat(state.freezeUsedOn()).isNull();

        assertThat(StreakRules.record(state, Side.B, day(1))).isSameAs(state);
        assertThat(state.count()).isEqualTo(1);
        assertThat(state.freezeUsedOn()).isNull();
    }

    @Test
    void aStaleRecordForADayAlreadyBridgedByGraceDoesNotCompleteItAgain() {
        // A and B were both on day 1, then both jumped to day 3 (day 2
        // skipped): day 3 completes across the one-day gap and spends the
        // grace day on day 2. B's day-2 send was real but its record lands
        // only now — day 2 must not complete (or count) a second time.
        State state = both(both(State.EMPTY, day(0)), day(1));
        state = StreakRules.record(state, Side.A, day(3));
        state = StreakRules.record(state, Side.B, day(3));

        assertThat(state.count()).isEqualTo(3);
        assertThat(state.freezeUsedOn()).isEqualTo(day(2));

        state = StreakRules.record(state, Side.B, day(2));

        assertThat(state.count()).isEqualTo(3);
        assertThat(state.lastDay()).isEqualTo(day(3));
        assertThat(state.freezeUsedOn()).isEqualTo(day(2));
    }

    @Test
    void theReverseSideOfTheCrossMidnightRaceStillCompletes() {
        State state = both(State.EMPTY, day(0));
        state = StreakRules.record(state, Side.B, day(1));
        state = StreakRules.record(state, Side.B, day(2));
        state = StreakRules.record(state, Side.A, day(1));
        state = StreakRules.record(state, Side.A, day(2));

        assertThat(state.count()).isEqualTo(3);
        assertThat(state.lastDay()).isEqualTo(day(2));
        assertThat(state.freezeUsedOn()).isNull();
    }

    @Test
    void bothSidesTakeTurnsBeingStaleAndEveryDayStillCompletes() {
        // Two back-to-back races: first A's records run ahead of B's day 1,
        // then B's run ahead of A's day 3. Every day was sent by both sides.
        State state = both(State.EMPTY, day(0));
        state = StreakRules.record(state, Side.A, day(1));
        state = StreakRules.record(state, Side.A, day(2));
        state = StreakRules.record(state, Side.B, day(1));   // B stale on day 1
        state = StreakRules.record(state, Side.B, day(2));
        state = StreakRules.record(state, Side.B, day(3));
        state = StreakRules.record(state, Side.B, day(4));
        state = StreakRules.record(state, Side.A, day(3));   // A stale on day 3
        state = StreakRules.record(state, Side.A, day(4));

        assertThat(state.count()).isEqualTo(5);
        assertThat(state.lastDay()).isEqualTo(day(4));
        assertThat(state.freezeUsedOn()).isNull();
    }

    @Test
    void aRealSkipStillDoesNotCompleteEvenWhenTheOtherSideSentTheAloneDay() {
        // A skipped day 1 entirely; B sent it. Day 1 must stay incomplete no
        // matter that B's record is fresh, and completing day 2 later bridges
        // day 1 with the grace day instead of counting it.
        State state = both(State.EMPTY, day(0));
        state = StreakRules.record(state, Side.A, day(2));
        state = StreakRules.record(state, Side.B, day(1));

        assertThat(state.count()).isEqualTo(1);
        assertThat(state.lastDay()).isEqualTo(day(0));

        state = StreakRules.record(state, Side.B, day(2));

        assertThat(state.count()).isEqualTo(2);
        assertThat(state.freezeUsedOn()).isEqualTo(day(1));
    }

    @Test
    void oneMissedDayIsBridgedByTheGraceDayAndNotCounted() {
        State state = both(both(State.EMPTY, day(0)), day(1));

        state = both(state, day(3));

        assertThat(state.count()).isEqualTo(3);
        assertThat(state.freezeUsedOn()).isEqualTo(day(2));
        assertThat(state.startedOn()).isEqualTo(day(0));
        assertThat(state.lastDay()).isEqualTo(day(3));
    }

    @Test
    void aSecondMissWithinAWeekOfTheGraceDayResetsTheChain() {
        State state = both(both(State.EMPTY, day(0)), day(1));
        state = both(state, day(3));          // grace day consumed on day 2
        state = both(state, day(5));          // second one-day miss, still inside the week

        assertThat(state.count()).isEqualTo(1);
        assertThat(state.startedOn()).isEqualTo(day(5));
        assertThat(state.bestCount()).isEqualTo(3);
    }

    @Test
    void theGraceDayComesBackSevenDaysAfterItWasUsed() {
        State state = both(both(State.EMPTY, day(0)), day(1));
        state = both(state, day(3));          // grace used on day 2
        for (int i = 4; i <= 9; i++) {
            state = both(state, day(i));
        }
        assertThat(state.count()).isEqualTo(9);

        state = both(state, day(11));         // miss day 10: day 2 is more than six days back

        assertThat(state.count()).isEqualTo(10);
        assertThat(state.freezeUsedOn()).isEqualTo(day(10));
    }

    @Test
    void theGraceDayIsStillSpentSixDaysAfterItWasUsed() {
        State state = both(both(State.EMPTY, day(0)), day(1));
        state = both(state, day(3));          // grace used on day 2
        for (int i = 4; i <= 6; i++) {
            state = both(state, day(i));
        }

        state = both(state, day(8));          // miss day 7: only six days after the grace day

        assertThat(state.count()).isEqualTo(1);
    }

    @Test
    void aGapOfTwoOrMoreMissedDaysResets() {
        State state = both(both(State.EMPTY, day(0)), day(1));

        state = both(state, day(4));

        assertThat(state.count()).isEqualTo(1);
        assertThat(state.startedOn()).isEqualTo(day(4));
        assertThat(state.freezeUsedOn()).isNull();
        assertThat(state.bestCount()).isEqualTo(2);
    }

    @Test
    void bestCountSurvivesAReset() {
        State state = State.EMPTY;
        for (int i = 0; i < 5; i++) {
            state = both(state, day(i));
        }
        state = both(state, day(20));

        assertThat(state.count()).isEqualTo(1);
        assertThat(state.bestCount()).isEqualTo(5);
    }

    @Test
    void viewIsAliveAndAtRiskTheDayAfter() {
        State state = both(both(State.EMPTY, day(0)), day(1));

        View view = StreakRules.view(state, day(2));

        assertThat(view.count()).isEqualTo(2);
        assertThat(view.atRisk()).isTrue();
        assertThat(view.startedOn()).isEqualTo(day(0));
        assertThat(view.sideADoneToday()).isFalse();
    }

    @Test
    void viewIsNotAtRiskOnceTodayIsComplete() {
        State state = both(both(State.EMPTY, day(0)), day(1));

        View view = StreakRules.view(state, day(1));

        assertThat(view.count()).isEqualTo(2);
        assertThat(view.atRisk()).isFalse();
        assertThat(view.sideADoneToday()).isTrue();
        assertThat(view.sideBDoneToday()).isTrue();
    }

    @Test
    void viewTracksWhichSideHasSentToday() {
        State state = StreakRules.record(both(State.EMPTY, day(0)), Side.A, day(1));

        View view = StreakRules.view(state, day(1));

        assertThat(view.sideADoneToday()).isTrue();
        assertThat(view.sideBDoneToday()).isFalse();
        assertThat(view.atRisk()).isTrue();
    }

    @Test
    void viewStaysAliveAcrossOneMissedDayWhenTheGraceDayIsFree() {
        State state = both(both(State.EMPTY, day(0)), day(1));

        View view = StreakRules.view(state, day(3));

        assertThat(view.count()).isEqualTo(2);
        assertThat(view.atRisk()).isTrue();
        assertThat(view.freezeAvailable()).isTrue();
    }

    @Test
    void viewReadsZeroOnceTheChainCannotBeSaved() {
        State state = both(both(State.EMPTY, day(0)), day(1));

        View view = StreakRules.view(state, day(4));

        assertThat(view.count()).isZero();
        assertThat(view.startedOn()).isNull();
        assertThat(view.atRisk()).isFalse();
        assertThat(view.milestone()).isZero();
        assertThat(view.bestCount()).isEqualTo(2);
    }

    @Test
    void viewReadsZeroWhenTheGraceDayIsAlreadySpent() {
        State state = both(both(State.EMPTY, day(0)), day(1));
        state = both(state, day(3));                    // grace used on day 2

        assertThat(StreakRules.view(state, day(5)).count()).isZero();
        assertThat(StreakRules.view(state, day(4)).count()).isEqualTo(3);
    }

    @Test
    void emptyStateReadsZero() {
        View view = StreakRules.view(State.EMPTY, day(0));

        assertThat(view.count()).isZero();
        assertThat(view.atRisk()).isFalse();
        assertThat(view.lastDay()).isNull();
        assertThat(view.freezeAvailable()).isFalse();
    }

    @Test
    void viewReportsTheGraceDayWhileAMissTodayWouldStillBeBridged() {
        // The grace day was spent on day 2. Today (day 8) is six days later,
        // but the completing send lands on day 9 — outside the cooldown — so
        // a miss today would still be bridged and the flag must say so.
        State state = new State(day(7), day(7), 4, 4, day(7), day(0), day(2), day(0), day(0));

        assertThat(StreakRules.view(state, day(8)).freezeAvailable()).isTrue();
    }

    @Test
    void viewStopsReportingTheGraceDayOnceTheBridgeWouldPassTheCooldown() {
        // The grace day was spent on day 3. A miss today would be completed
        // on day 9 — exactly the strict-cooldown boundary — so it would not
        // be bridged.
        State state = new State(day(7), day(7), 4, 4, day(7), day(0), day(3), day(0), day(0));

        assertThat(StreakRules.view(state, day(8)).freezeAvailable()).isFalse();
    }

    @Test
    void aLapsedChainHasNoGraceDayLeftToSpend() {
        State state = both(both(State.EMPTY, day(0)), day(1));

        assertThat(StreakRules.view(state, day(4)).freezeAvailable()).isFalse();
        assertThat(StreakRules.view(state, day(2)).freezeAvailable()).isTrue();
    }

    @Test
    void milestonesAreThreeSevenThirtyAndHundred() {
        assertThat(StreakRules.milestone(0)).isZero();
        assertThat(StreakRules.milestone(2)).isZero();
        assertThat(StreakRules.milestone(3)).isEqualTo(3);
        assertThat(StreakRules.milestone(6)).isEqualTo(3);
        assertThat(StreakRules.milestone(7)).isEqualTo(7);
        assertThat(StreakRules.milestone(29)).isEqualTo(7);
        assertThat(StreakRules.milestone(30)).isEqualTo(30);
        assertThat(StreakRules.milestone(99)).isEqualTo(30);
        assertThat(StreakRules.milestone(100)).isEqualTo(100);
        assertThat(StreakRules.milestone(5_000)).isEqualTo(100);
    }

    @Test
    void viewReportsTheMilestoneReachedByTheLiveCount() {
        State state = State.EMPTY;
        for (int i = 0; i < 7; i++) {
            state = both(state, day(i));
        }

        assertThat(StreakRules.view(state, day(6)).milestone()).isEqualTo(7);
        assertThat(StreakRules.view(state, day(30)).milestone()).isZero();
    }

    @Test
    void streakDayFollowsTheConfiguredZone() {
        StreakProperties properties = new StreakProperties();
        properties.setZone("Asia/Ho_Chi_Minh");
        StreakService service = new StreakService(null, properties, java.time.Clock.systemUTC(),
                null, null);
        Instant justBeforeLocalMidnight = Instant.parse("2026-11-16T16:59:59Z");
        Instant justAfterLocalMidnight = Instant.parse("2026-11-16T17:00:00Z");

        assertThat(service.dayOf(justBeforeLocalMidnight)).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(service.dayOf(justAfterLocalMidnight)).isEqualTo(LocalDate.of(2026, 11, 17));
        assertThat(justAfterLocalMidnight.atZone(ZoneId.of("UTC")).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 11, 16));
    }

    @Test
    void defaultZoneIsUtc() {
        StreakService service = new StreakService(null, new StreakProperties(),
                java.time.Clock.systemUTC(), null, null);

        assertThat(service.dayOf(Instant.parse("2026-11-16T23:59:59Z")))
                .isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(service.dayOf(Instant.parse("2026-11-17T00:00:00Z")))
                .isEqualTo(LocalDate.of(2026, 11, 17));
    }
}
