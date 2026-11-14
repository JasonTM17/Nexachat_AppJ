package com.nexachat.data;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nexachat.core.database.AppDatabase;
import com.nexachat.core.database.OutboxDao;
import com.nexachat.core.network.SessionEvents;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import io.reactivex.rxjava3.subjects.SingleSubject;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class AccountDataCleanerTest {
    @Test
    public void cleanupWaitsForWorkerCancellationThenPreservesMessageOutbox() {
        AppDatabase database = mock(AppDatabase.class);
        SessionOperationTracker tracker = new SessionOperationTracker();
        CompletableSubject workerCancellation = CompletableSubject.create();
        AccountDataCleaner cleaner = new AccountDataCleaner(
                database, tracker, () -> workerCancellation);

        var first = cleaner.clearForSessionBoundary().test();
        var concurrentLogin = cleaner.clearForSessionBoundary().test();

        first.assertNotComplete();
        concurrentLogin.assertNotComplete();
        verify(database, never()).clearAccountDataPreservingOutbox();

        workerCancellation.onComplete();

        first.awaitDone(5, TimeUnit.SECONDS).assertComplete();
        concurrentLogin.awaitDone(5, TimeUnit.SECONDS).assertComplete();
        assertFalse(tracker.isActive());
        verify(database, org.mockito.Mockito.times(2)).clearAccountDataPreservingOutbox();
    }

    @Test
    public void logoutQueuedDuringLoginOwnsItsTokenAndEventFinishBoundary() {
        AppDatabase database = mock(AppDatabase.class);
        SessionOperationTracker tracker = new SessionOperationTracker();
        CompletableSubject workerCancellation = CompletableSubject.create();
        OutboxDao outboxDao = mock(OutboxDao.class);
        when(database.outboxDao()).thenReturn(outboxDao);
        AccountDataCleaner cleaner = new AccountDataCleaner(
                database, tracker, () -> workerCancellation);
        SessionEvents events = new SessionEvents();
        var eventObserver = events.getEvents().test();
        AtomicInteger tokenSaves = new AtomicInteger();
        AtomicInteger tokenClears = new AtomicInteger();
        List<String> order = new ArrayList<>();
        when(outboxDao.sealUnassignedLegacyOwners()).thenReturn(
                Completable.fromAction(() -> order.add("seal-legacy-outbox")));
        doAnswer(invocation -> {
            order.add("clear-room");
            return null;
        }).when(database).clearAccountDataPreservingOutbox();

        var login = cleaner.startSession(
                io.reactivex.rxjava3.core.Completable.fromAction(() -> {
                    tokenSaves.incrementAndGet();
                    order.add("save-login-token");
                }),
                io.reactivex.rxjava3.core.Completable.fromAction(events::emitSessionAvailable))
                .test();
        var logout = cleaner.clearForLogout(
                io.reactivex.rxjava3.core.Completable.fromAction(() -> {
                    tokenClears.incrementAndGet();
                    order.add("clear-logout-token");
                    events.emitLoggedOut();
                })).test();

        login.assertNotComplete();
        logout.assertNotComplete();
        assertFalse(tracker.isActive());

        workerCancellation.onComplete();

        login.awaitDone(5, TimeUnit.SECONDS).assertError(CancellationException.class);
        logout.awaitDone(5, TimeUnit.SECONDS).assertComplete();
        assertEquals(1, tokenSaves.get());
        assertEquals(1, tokenClears.get());
        assertEquals(List.of(
                "clear-room", "seal-legacy-outbox", "save-login-token",
                "clear-room", "clear-logout-token"), order);
        assertEquals(1, eventObserver.values().size());
        assertTrue(eventObserver.values().get(0) instanceof SessionEvents.Event.LoggedOut);
        assertFalse(tracker.isActive());
        verify(database, org.mockito.Mockito.times(2)).clearAccountDataPreservingOutbox();
        verify(outboxDao).sealUnassignedLegacyOwners();
    }

    @Test
    public void logoutAllExplicitlyClearsThePreservedOutbox() {
        AppDatabase database = mock(AppDatabase.class);
        AccountDataCleaner cleaner = new AccountDataCleaner(
                database, new SessionOperationTracker(), Completable::complete);

        cleaner.clearForLogout(Completable.complete(), true).blockingAwait();

        verify(database).clearAllTables();
        verify(database, never()).clearAccountDataPreservingOutbox();
    }

    @Test
    public void lateWorkerCompletionAfterAccountSwitchCannotWriteOldAccountData() {
        AppDatabase database = mock(AppDatabase.class);
        OutboxDao outboxDao = mock(OutboxDao.class);
        when(database.outboxDao()).thenReturn(outboxDao);
        when(outboxDao.sealUnassignedLegacyOwners()).thenReturn(Completable.complete());
        SessionOperationTracker tracker = new SessionOperationTracker();
        AccountDataCleaner cleaner = new AccountDataCleaner(
                database, tracker, Completable::complete);
        SingleSubject<String> oldAccountWorker = SingleSubject.create();
        AtomicInteger oldAccountWrites = new AtomicInteger();

        var worker = tracker.track(oldAccountWorker.doOnSuccess(ignored -> oldAccountWrites.incrementAndGet()))
                .test();
        cleaner.startSession(Completable.complete(), Completable.complete()).blockingAwait();
        assertTrue("new account session should be active", tracker.isActive());

        oldAccountWorker.onSuccess("late old account result");

        worker.assertNoValues();
        assertEquals("disposed worker callback must not write for its old account",
                0, oldAccountWrites.get());
    }

    @Test
    public void signInResponseFromBeforeALaterBoundaryIsDroppedWithoutTouchingTheNewSession() {
        AppDatabase database = mock(AppDatabase.class);
        OutboxDao outboxDao = mock(OutboxDao.class);
        when(database.outboxDao()).thenReturn(outboxDao);
        when(outboxDao.sealUnassignedLegacyOwners()).thenReturn(Completable.complete());
        SessionOperationTracker tracker = new SessionOperationTracker();
        AccountDataCleaner cleaner = new AccountDataCleaner(database, tracker, Completable::complete);
        AtomicInteger staleSaves = new AtomicInteger();

        long startedUnder = cleaner.sessionBoundary();
        cleaner.clearForLogout(Completable.complete()).blockingAwait();
        cleaner.startSession(Completable.complete(), Completable.complete()).blockingAwait();
        verify(database, org.mockito.Mockito.times(2)).clearAccountDataPreservingOutbox();

        cleaner.startSession(Completable.fromAction(staleSaves::incrementAndGet),
                        Completable.complete(), startedUnder)
                .test().awaitDone(5, TimeUnit.SECONDS).assertError(CancellationException.class);

        assertEquals("stale response must not store its credentials", 0, staleSaves.get());
        assertTrue("the newer session stays active", tracker.isActive());
        verify(database, org.mockito.Mockito.times(2)).clearAccountDataPreservingOutbox();
    }

    @Test
    public void signInStartedUnderTheCurrentBoundaryStillEstablishesTheSession() {
        AppDatabase database = mock(AppDatabase.class);
        OutboxDao outboxDao = mock(OutboxDao.class);
        when(database.outboxDao()).thenReturn(outboxDao);
        when(outboxDao.sealUnassignedLegacyOwners()).thenReturn(Completable.complete());
        SessionOperationTracker tracker = new SessionOperationTracker();
        AccountDataCleaner cleaner = new AccountDataCleaner(database, tracker, Completable::complete);
        AtomicInteger saves = new AtomicInteger();

        cleaner.startSession(Completable.fromAction(saves::incrementAndGet), Completable.complete(),
                cleaner.sessionBoundary()).blockingAwait();

        assertEquals(1, saves.get());
        assertTrue(tracker.isActive());
    }
}
