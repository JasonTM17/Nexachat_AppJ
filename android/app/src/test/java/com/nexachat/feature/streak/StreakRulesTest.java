package com.nexachat.feature.streak;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.LocalDate;
import org.junit.Test;

public final class StreakRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 14);

    @Test public void badgeAppearsFromTheSecondDay() {
        assertFalse(StreakRules.isBadgeVisible(0));
        assertFalse(StreakRules.isBadgeVisible(1));
        assertTrue(StreakRules.isBadgeVisible(2));
        assertTrue(StreakRules.isBadgeVisible(30));
    }

    @Test public void onlyListedLengthsAreMilestones() {
        assertTrue(StreakRules.isMilestone(3));
        assertTrue(StreakRules.isMilestone(7));
        assertTrue(StreakRules.isMilestone(30));
        assertTrue(StreakRules.isMilestone(100));
        assertFalse(StreakRules.isMilestone(0));
        assertFalse(StreakRules.isMilestone(2));
        assertFalse(StreakRules.isMilestone(8));
        assertFalse(StreakRules.isMilestone(101));
    }

    @Test public void celebrationKeySeparatesChainsAndScopes() {
        assertEquals("checkin|2026-03-01|7",
                StreakRules.celebrationKey("checkin", "2026-03-01", 7));
        assertEquals("checkin||7", StreakRules.celebrationKey("checkin", null, 7));
        assertFalse(StreakRules.celebrationKey("checkin", "2026-03-01", 7)
                .equals(StreakRules.celebrationKey("checkin", "2026-03-02", 7)));
        assertFalse(StreakRules.celebrationKey("checkin", "2026-03-01", 7)
                .equals(StreakRules.celebrationKey("conversation-1", "2026-03-01", 7)));
    }

    @Test public void checkInIsDueWithoutAStoredDay() {
        assertTrue(StreakRules.shouldCheckIn(null, TODAY));
        assertTrue(StreakRules.shouldCheckIn("", TODAY));
        assertTrue(StreakRules.shouldCheckIn("   ", TODAY));
    }

    @Test public void checkInIsDueOnlyAfterTheStoredDay() {
        assertFalse(StreakRules.shouldCheckIn("2026-03-14", TODAY));
        assertTrue(StreakRules.shouldCheckIn("2026-03-13", TODAY));
        assertTrue(StreakRules.shouldCheckIn("2026-02-28", TODAY));
    }

    @Test public void aStoredDayAheadOfTheDeviceStillCountsAsDone() {
        assertFalse(StreakRules.shouldCheckIn("2026-03-15", TODAY));
    }

    @Test public void malformedStoredDayRetries() {
        assertTrue(StreakRules.shouldCheckIn("14/03/2026", TODAY));
        assertTrue(StreakRules.shouldCheckIn("not-a-day", TODAY));
    }

    @Test public void refreshIsUsefulUntilBothSidesSentToday() {
        assertTrue(StreakRules.needsRefreshAfterActivity(null));
        assertTrue(StreakRules.needsRefreshAfterActivity(streak(false, false)));
        assertTrue(StreakRules.needsRefreshAfterActivity(streak(true, false)));
        assertTrue(StreakRules.needsRefreshAfterActivity(streak(false, true)));
        assertFalse(StreakRules.needsRefreshAfterActivity(streak(true, true)));
    }

    private static StreakDto streak(boolean mySentToday, boolean peerSentToday) {
        return new StreakDto(4, 9, "2026-03-10", "2026-03-14", false, 0,
                mySentToday, peerSentToday);
    }
}
