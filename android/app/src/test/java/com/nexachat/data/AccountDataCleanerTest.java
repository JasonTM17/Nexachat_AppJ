package com.nexachat.data;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.nexachat.core.database.AppDatabase;
import com.nexachat.core.network.SessionEvents;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class AccountDataCleanerTest {
    @Test
    public void cleanupWaitsForWorkerCancellationThenClearsEveryRoomTable() {
        AppDatabase database = mock(AppDatabase.class);
        SessionOperationTracker tracker = new SessionOperationTracker();
        CompletableSubject workerCancellation = CompletableSubject.create();
        AccountDataCleaner cleaner = new AccountDataCleaner(
                database, tracker, () -> workerCancellation);

        var first = cleaner.clearForSessionBoundary().test();
        var concurrentLogin = cleaner.clearForSessionBoundary().test();

        first.assertNotComplete();
        concurrentLogin.assertNotComplete();
        verify(database, never()).clearAllTables();

        workerCancellation.onComplete();

        first.awaitDone(5, TimeUnit.SECONDS).assertComplete();
        concurrentLogin.awaitDone(5, TimeUnit.SECONDS).assertComplete();
        assertFalse(tracker.isActive());
        verify(database, org.mockito.Mockito.times(2)).clearAllTables();
    }

    @Test
    public void logoutQueuedDuringLoginOwnsItsTokenAndEventFinishBoundary() {
        AppDatabase database = mock(AppDatabase.class);
        SessionOperationTracker tracker = new SessionOperationTracker();
        CompletableSubject workerCancellation = CompletableSubject.create();
        AccountDataCleaner cleaner = new AccountDataCleaner(
                database, tracker, () -> workerCancellation);
        SessionEvents events = new SessionEvents();
        var eventObserver = events.getEvents().test();
        AtomicInteger tokenSaves = new AtomicInteger();
        AtomicInteger tokenClears = new AtomicInteger();
        List<String> order = new ArrayList<>();
        doAnswer(invocation -> {
            order.add("clear-room");
            return null;
        }).when(database).clearAllTables();

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
                "clear-room", "save-login-token", "clear-room", "clear-logout-token"), order);
        assertEquals(1, eventObserver.values().size());
        assertTrue(eventObserver.values().get(0) instanceof SessionEvents.Event.LoggedOut);
        assertFalse(tracker.isActive());
        verify(database, org.mockito.Mockito.times(2)).clearAllTables();
    }
}
