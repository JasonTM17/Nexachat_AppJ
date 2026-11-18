package com.nexachat.feature.streak;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import com.nexachat.core.network.ApiCalls;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public final class StreakViewModelTest {

    @Rule public final InstantTaskExecutorRule instantTaskExecutorRule =
            new InstantTaskExecutorRule();

    private FakeRepository repository;
    private FakeStore store;
    private MilestoneTracker tracker;
    private StreakViewModel viewModel;

    @Before public void setUp() {
        repository = new FakeRepository();
        store = new FakeStore();
        tracker = new MilestoneTracker(store);
        viewModel = new StreakViewModel(repository, tracker);
    }

    @Test public void aMilestoneIsPublishedWithoutBeingPersisted() {
        loadMine(7, "2026-03-01");

        MilestoneTracker.Celebration celebration = viewModel.getCelebration().getValue();
        assertNotNull(celebration);
        assertEquals(7, celebration.getMilestone());
        assertTrue(store.celebrated.isEmpty());
    }

    @Test public void aShownSheetPersistsTheCelebrationAndClearsTheEvent() {
        loadMine(7, "2026-03-01");
        MilestoneTracker.Celebration celebration = viewModel.getCelebration().getValue();

        viewModel.onCelebrationShown(celebration);

        assertNull(viewModel.getCelebration().getValue());
        assertTrue(store.isCelebrated(celebration.key()));
    }

    @Test public void aSheetThatCouldNotShowLeavesEveryQueuedCelebrationPending() {
        repository.mine = streak(3, "2026-03-01");
        viewModel.loadMine();
        MilestoneTracker.Celebration first = viewModel.getCelebration().getValue();
        // Two more candidates are queued behind the one handed out.
        tracker.offer("conversation-1", streak(7, "2026-03-01"));
        tracker.offer("conversation-2", streak(30, "2026-03-01"));

        viewModel.onCelebrationNotShown(first);

        assertNull("no immediate re-publish", viewModel.getCelebration().getValue());
        assertTrue("nothing persisted", store.celebrated.isEmpty());
        List<Integer> offered = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            MilestoneTracker.Celebration next = tracker.next();
            assertNotNull(next);
            offered.add(next.getMilestone());
            tracker.shown(next);
            tracker.dismissed();
        }
        assertEquals(List.of(30, 7, 3), offered);
        assertNull(tracker.next());
    }

    @Test public void aCelebrationThatCouldNotShowIsOfferedAgainOnTheNextLoad() {
        loadMine(7, "2026-03-01");
        MilestoneTracker.Celebration celebration = viewModel.getCelebration().getValue();
        viewModel.onCelebrationNotShown(celebration);

        viewModel.loadMine();

        assertEquals(celebration, viewModel.getCelebration().getValue());
        assertFalse(store.isCelebrated(celebration.key()));
    }

    @Test public void theNextCelebrationFollowsOnlyAfterTheSheetIsDismissed() {
        repository.mine = streak(7, "2026-03-01");
        viewModel.loadMine();
        MilestoneTracker.Celebration first = viewModel.getCelebration().getValue();
        tracker.offer("conversation-1", streak(3, "2026-03-01"));

        viewModel.onCelebrationShown(first);
        assertNull(viewModel.getCelebration().getValue());

        viewModel.onCelebrationDismissed();

        assertEquals(3, viewModel.getCelebration().getValue().getMilestone());
    }

    private void loadMine(int milestone, String startedOn) {
        repository.mine = streak(milestone, startedOn);
        viewModel.loadMine();
    }

    private static StreakDto streak(int milestone, String startedOn) {
        return new StreakDto(
                milestone, milestone, startedOn, "2026-03-14", false, milestone, true, true);
    }

    private static final class FakeRepository implements StreakRepository {
        private StreakDto mine;

        @Override public ApiCalls.Cancellable loadMine(Callback<StreakDto> callback) {
            callback.onSuccess(mine);
            return () -> { };
        }

        @Override public ApiCalls.Cancellable loadConversation(
                String conversationId, Callback<StreakDto> callback) {
            return () -> { };
        }

        @Override public boolean isCheckInDue() {
            return false;
        }

        @Override public ApiCalls.Cancellable checkIn(Callback<StreakDto> callback) {
            return () -> { };
        }
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
