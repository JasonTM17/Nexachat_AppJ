package com.nexachat.feature.streak;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

public final class MilestoneTrackerTest {

    private FakeStore store;
    private MilestoneTracker tracker;

    @Before public void setUp() {
        store = new FakeStore();
        tracker = new MilestoneTracker(store);
    }

    @Test public void offersOnlyReachedMilestones() {
        tracker.offer("checkin", streak(2, 0, "2026-03-01"));
        tracker.offer("checkin", streak(4, 0, "2026-03-01"));

        assertNull(tracker.next());
    }

    @Test public void offersAMilestoneOncePerChain() {
        tracker.offer("checkin", streak(7, 7, "2026-03-01"));

        MilestoneTracker.Celebration celebration = tracker.next();
        assertEquals(7, celebration.getMilestone());
        assertEquals(7, celebration.getCount());
        assertEquals("checkin", celebration.getScope());
        assertEquals("2026-03-01", celebration.getStartedOn());
        tracker.shown(celebration);
        assertTrue(store.isCelebrated(celebration.key()));

        tracker.dismissed();
        tracker.offer("checkin", streak(7, 7, "2026-03-01"));
        assertNull(tracker.next());
    }

    @Test public void aHandedOutCelebrationIsNotPersistedUntilItIsShown() {
        tracker.offer("checkin", streak(7, 7, "2026-03-01"));

        MilestoneTracker.Celebration celebration = tracker.next();

        assertFalse(store.isCelebrated(celebration.key()));
    }

    @Test public void aHandedOutCelebrationSurvivesProcessDeathBeforeItIsShown() {
        tracker.offer("checkin", streak(7, 7, "2026-03-01"));
        tracker.next();

        MilestoneTracker afterRestart = new MilestoneTracker(store);
        afterRestart.offer("checkin", streak(7, 7, "2026-03-01"));

        assertEquals(7, afterRestart.next().getMilestone());
    }

    @Test public void aShownCelebrationIsPersistedAndNotOfferedAgain() {
        tracker.offer("checkin", streak(7, 7, "2026-03-01"));
        MilestoneTracker.Celebration celebration = tracker.next();

        tracker.shown(celebration);
        tracker.dismissed();

        assertTrue(store.isCelebrated(celebration.key()));
        tracker.offer("checkin", streak(7, 7, "2026-03-01"));
        assertNull(tracker.next());
    }

    @Test public void aCelebrationThatWasNotShownReturnsToTheQueueUnpersisted() {
        tracker.offer("checkin", streak(7, 7, "2026-03-01"));
        MilestoneTracker.Celebration celebration = tracker.next();

        tracker.notShown(celebration);

        assertFalse(store.isCelebrated(celebration.key()));
        assertEquals(celebration, tracker.next());
    }

    @Test public void aCelebrationNotShownKeepsTheWholeQueueEligible() {
        tracker.offer("checkin", streak(3, 3, "2026-03-01"));
        tracker.offer("checkin", streak(7, 7, "2026-03-01"));
        tracker.offer("checkin", streak(30, 30, "2026-03-01"));

        MilestoneTracker.Celebration first = tracker.next();
        tracker.notShown(first);

        assertEquals(30, first.getMilestone());
        assertEquals(0, store.celebrated.size());
        assertEquals(30, tracker.next().getMilestone());
    }

    @Test public void aNewChainCelebratesTheSameMilestoneAgain() {
        tracker.offer("checkin", streak(7, 7, "2026-03-01"));
        tracker.shown(tracker.next());
        tracker.dismissed();

        tracker.offer("checkin", streak(7, 7, "2026-04-01"));

        assertEquals("2026-04-01", tracker.next().getStartedOn());
    }

    @Test public void theHighestQueuedMilestoneWins() {
        tracker.offer("checkin", streak(3, 3, "2026-03-01"));
        tracker.offer("checkin", streak(30, 30, "2026-03-01"));

        MilestoneTracker.Celebration first = tracker.next();
        assertEquals(30, first.getMilestone());
        tracker.shown(first);
        tracker.dismissed();
        assertEquals(3, tracker.next().getMilestone());
    }

    @Test public void nothingIsHandedOutWhileOneIsShowing() {
        tracker.offer("checkin", streak(3, 3, "2026-03-01"));
        tracker.offer("checkin", streak(7, 7, "2026-03-01"));

        assertEquals(7, tracker.next().getMilestone());
        assertNull(tracker.next());

        tracker.dismissed();
        assertEquals(3, tracker.next().getMilestone());
    }

    @Test public void aCountBelowTheMilestoneIsNotCelebrated() {
        tracker.offer("checkin", streak(3, 7, "2026-03-01"));

        assertNull(tracker.next());
    }

    @Test public void theSameCandidateIsQueuedOnce() {
        tracker.offer("checkin", streak(7, 7, "2026-03-01"));
        tracker.offer("checkin", streak(7, 7, "2026-03-01"));

        tracker.next();
        tracker.dismissed();
        assertNull(tracker.next());
    }

    @Test public void discardPendingDropsOnlyThatScope() {
        tracker.offer("checkin", streak(7, 7, "2026-03-01"));
        tracker.offer("conversation-1", streak(7, 7, "2026-03-01"));

        tracker.discardPending("conversation-1");

        assertEquals("checkin", tracker.next().getScope());
        tracker.dismissed();
        assertNull(tracker.next());
    }

    @Test public void clearForgetsQueuedAndShowingState() {
        tracker.offer("checkin", streak(7, 7, "2026-03-01"));
        tracker.shown(tracker.next());

        tracker.clear();

        assertNull(tracker.next());
        tracker.offer("checkin", streak(7, 7, "2026-03-01"));
        assertNull("a celebrated key stays celebrated", tracker.next());
    }

    @Test public void celebrationEqualityIsTheKey() {
        MilestoneTracker.Celebration first =
                new MilestoneTracker.Celebration("checkin", "2026-03-01", 7, 7);
        MilestoneTracker.Celebration same =
                new MilestoneTracker.Celebration("checkin", "2026-03-01", 7, 9);
        MilestoneTracker.Celebration other =
                new MilestoneTracker.Celebration("checkin", "2026-03-02", 7, 7);

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertFalse(first.equals(other));
    }

    private static StreakDto streak(int count, int milestone, String startedOn) {
        return new StreakDto(count, count, startedOn, "2026-03-14", false, milestone, true, true);
    }

    private static final class FakeStore implements StreakStore {
        private final Set<String> celebrated = new HashSet<>();
        private String lastCheckInDay;

        @Override public String lastCheckInDay() {
            return lastCheckInDay;
        }

        @Override public void saveLastCheckInDay(String day) {
            lastCheckInDay = day;
        }

        @Override public boolean isCelebrated(String key) {
            return celebrated.contains(key);
        }

        @Override public void markCelebrated(String key) {
            celebrated.add(key);
        }

        @Override public void clear() {
            celebrated.clear();
            lastCheckInDay = null;
        }
    }
}
