package com.nexachat.feature.streak;

import static org.junit.Assert.assertEquals;

import com.nexachat.core.network.ApiCalls;
import io.reactivex.rxjava3.core.Single;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

public final class StreakCheckInCoordinatorTest {

    private FakeRepository repository;
    private FakeStore store;
    private MilestoneTracker milestoneTracker;
    private StreakCheckInCoordinator coordinator;

    @Before public void setUp() {
        repository = new FakeRepository();
        store = new FakeStore();
        milestoneTracker = new MilestoneTracker(store);
        coordinator = new StreakCheckInCoordinator(
                repository, () -> Single.just(true), store, milestoneTracker);
    }

    @Test public void aSignedOutForegroundSendsNothing() {
        coordinator = new StreakCheckInCoordinator(
                repository, () -> Single.just(false), store, milestoneTracker);

        coordinator.onAppForeground();

        assertEquals(0, repository.checkInCalls);
    }

    @Test public void aDueForegroundSendsOneCheckIn() {
        repository.due = true;

        coordinator.onAppForeground();

        assertEquals(1, repository.checkInCalls);
    }

    @Test public void aForegroundAfterTodaysCheckInSendsNothing() {
        repository.due = false;

        coordinator.onAppForeground();

        assertEquals(0, repository.checkInCalls);
    }

    @Test public void aSecondForegroundWhileInFlightSendsNothing() {
        repository.due = true;
        repository.holdCallbacks = true;

        coordinator.onAppForeground();
        coordinator.onAppForeground();

        assertEquals(1, repository.checkInCalls);
    }

    @Test public void aCompletedCheckInAllowsTheNextOne() {
        repository.due = true;
        repository.holdCallbacks = true;
        coordinator.onAppForeground();

        repository.succeed();
        coordinator.onAppForeground();

        assertEquals(2, repository.checkInCalls);
    }

    @Test public void aFailedCheckInIsRetriedOnTheNextForeground() {
        repository.due = true;
        repository.holdCallbacks = true;
        coordinator.onAppForeground();

        repository.fail();
        coordinator.onAppForeground();

        assertEquals(2, repository.checkInCalls);
    }

    @Test public void aSynchronousCallbackDoesNotLeaveACallInFlight() {
        repository.due = true;
        repository.holdCallbacks = false;

        coordinator.onAppForeground();
        coordinator.onAppForeground();

        assertEquals(2, repository.checkInCalls);
    }

    @Test public void aSignedOutSupplierFailureIsIgnored() {
        coordinator = new StreakCheckInCoordinator(
                repository, () -> Single.error(new IllegalStateException("no session")),
                store, milestoneTracker);

        coordinator.onAppForeground();

        assertEquals(0, repository.checkInCalls);
    }

    private static final class FakeRepository implements StreakRepository {
        private boolean due;
        private boolean holdCallbacks;
        private int checkInCalls;
        private final List<Callback<StreakDto>> pending = new ArrayList<>();

        @Override public ApiCalls.Cancellable loadMine(Callback<StreakDto> callback) {
            return () -> { };
        }

        @Override public ApiCalls.Cancellable loadConversation(
                String conversationId, Callback<StreakDto> callback) {
            return () -> { };
        }

        @Override public boolean isCheckInDue() {
            return due;
        }

        @Override public ApiCalls.Cancellable checkIn(Callback<StreakDto> callback) {
            checkInCalls++;
            if (holdCallbacks) {
                pending.add(callback);
            } else {
                callback.onSuccess(new StreakDto(1, 1, "2026-03-14", "2026-03-14",
                        false, 0, true, false));
            }
            return () -> { };
        }

        void succeed() {
            List<Callback<StreakDto>> callbacks = new ArrayList<>(pending);
            pending.clear();
            for (Callback<StreakDto> callback : callbacks) {
                callback.onSuccess(new StreakDto(1, 1, "2026-03-14", "2026-03-14",
                        false, 0, true, false));
            }
        }

        void fail() {
            List<Callback<StreakDto>> callbacks = new ArrayList<>(pending);
            pending.clear();
            for (Callback<StreakDto> callback : callbacks) {
                callback.onFailure();
            }
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
