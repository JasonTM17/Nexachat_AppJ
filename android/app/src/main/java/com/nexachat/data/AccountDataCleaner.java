package com.nexachat.data;

import androidx.annotation.NonNull;
import com.nexachat.core.database.AppDatabase;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import io.reactivex.rxjava3.subjects.CompletableSubject;
import java.util.concurrent.CancellationException;
import java.util.Objects;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Serializes local Room cleanup at logout and explicit account login boundaries. */
@Singleton
public final class AccountDataCleaner {
    private final Object lock = new Object();
    private final AppDatabase database;
    private final Supplier<Completable> cancelOutbox;
    private final Runnable scheduleOutbox;
    private final SessionOperationTracker operationTracker;
    private CompletableSubject transitionTail;

    @Inject
    public AccountDataCleaner(
            @NonNull AppDatabase database,
            @NonNull OfflineOutboxScheduler outboxScheduler,
            @NonNull SessionOperationTracker operationTracker) {
        this.database = Objects.requireNonNull(database, "database");
        OfflineOutboxScheduler scheduler = Objects.requireNonNull(outboxScheduler, "outboxScheduler");
        this.cancelOutbox = scheduler::cancelPending;
        this.scheduleOutbox = scheduler::schedule;
        this.operationTracker = Objects.requireNonNull(operationTracker, "operationTracker");
    }

    AccountDataCleaner(
            @NonNull AppDatabase database,
            @NonNull SessionOperationTracker operationTracker,
            @NonNull Supplier<Completable> cancelOutbox) {
        this.database = Objects.requireNonNull(database, "database");
        this.operationTracker = Objects.requireNonNull(operationTracker, "operationTracker");
        this.cancelOutbox = Objects.requireNonNull(cancelOutbox, "cancelOutbox");
        this.scheduleOutbox = () -> {};
    }

    /** Binds ownerless v12 message rows only to the identity of a stored startup session. */
    @NonNull
    public Completable assignLegacyOutboxOwner(@NonNull String ownerUserId) {
        if (ownerUserId.isBlank()) return Completable.complete();
        return database.outboxDao().assignLegacyOwner(ownerUserId);
    }

    /** Closes the upgrade claim window when no stored session can prove ownership. */
    @NonNull
    public Completable sealUnassignedLegacyOutboxOwners() {
        return database.outboxDao().sealUnassignedLegacyOwners();
    }

    /** Schedules retained messages after startup restore confirms a session remains usable. */
    public void scheduleOutbox() {
        scheduleOutbox.run();
    }

    /** Invalidates accepted work, stops scheduled delivery, and clears account cache. */
    @NonNull
    public Completable clearForSessionBoundary() {
        return enqueueTransition(Completable.complete(), false, false, Completable.complete());
    }

    /**
     * Clears account data, persists the new session, then enables its work before
     * publishing availability. A newer queued boundary prevents a stale activation.
     */
    @NonNull
    public Completable startSession(
            @NonNull Completable persistSession,
            @NonNull Completable publishSessionAvailable) {
        return enqueueTransition(persistSession, true, false, publishSessionAvailable);
    }

    /** Serializes cleanup and token/event updates as one logout transition. */
    @NonNull
    public Completable clearForLogout(@NonNull Completable finishBoundary) {
        return clearForLogout(finishBoundary, false);
    }

    /** Explicit logout-all/account removal also deletes every queued send. */
    @NonNull
    public Completable clearForLogout(@NonNull Completable finishBoundary, boolean clearOutbox) {
        return enqueueTransition(finishBoundary, false, clearOutbox, Completable.complete());
    }

    private Completable enqueueTransition(
            @NonNull Completable finishBeforeActivation,
            boolean activateSession,
            boolean clearOutbox,
            @NonNull Completable finishAfterActivation) {
        return Completable.defer(() -> {
            Completable prior;
            CompletableSubject transition = CompletableSubject.create();
            long generation;
            synchronized (lock) {
                generation = operationTracker.invalidate();
                prior = transitionTail == null
                        ? Completable.complete()
                        : transitionTail.hide().onErrorComplete();
                transitionTail = transition;
            }
            prior.andThen(cancelOutbox.get())
                    .andThen(Completable.fromAction(() -> {
                        if (clearOutbox) database.clearAllTables();
                        else database.clearAccountDataPreservingOutbox();
                    }))
                    .andThen(Completable.defer(() -> activateSession
                            ? sealUnassignedLegacyOutboxOwners()
                            : Completable.complete()))
                    .andThen(finishBeforeActivation)
                    .andThen(activateSession
                            ? Completable.fromAction(() -> {
                                if (!operationTracker.activate(generation)) {
                                    throw new CancellationException(
                                            "A newer account boundary superseded login");
                                }
                            })
                            : Completable.complete())
                    .andThen(finishAfterActivation)
                    .subscribeOn(Schedulers.io())
                    .subscribe(() -> finish(transition, null),
                            error -> finish(transition, error));
            return transition.hide();
        });
    }

    private void finish(CompletableSubject barrier, Throwable error) {
        synchronized (lock) {
            if (transitionTail == barrier) transitionTail = null;
        }
        if (error == null) barrier.onComplete();
        else barrier.onError(error);
    }
}
