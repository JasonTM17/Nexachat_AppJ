package com.nexachat.data;

import static org.junit.Assert.assertEquals;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.subjects.SingleSubject;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class SessionOperationTrackerTest {
    @Test
    public void delayedAccountAResponseCannotWriteAfterAccountBCanStart() {
        SessionOperationTracker tracker = new SessionOperationTracker();
        SingleSubject<String> delayedResponse = SingleSubject.create();
        AtomicInteger persistedAccountA = new AtomicInteger();
        AtomicInteger persistedAccountB = new AtomicInteger();

        tracker.track(delayedResponse.flatMapCompletable(ignored ->
                Completable.fromAction(persistedAccountA::incrementAndGet)))
                .subscribe(() -> { }, ignored -> { });

        long generation = tracker.invalidate();
        tracker.activate(generation);
        tracker.track(Completable.fromAction(persistedAccountB::incrementAndGet))
                .test().assertComplete();
        delayedResponse.onSuccess("account-a-response");

        assertEquals(0, persistedAccountA.get());
        assertEquals(1, persistedAccountB.get());
    }
}
