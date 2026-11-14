package com.nexachat.data;

import androidx.annotation.NonNull;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.disposables.SerialDisposable;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.CancellationException;

/** Cancels account-scoped repository work at a local session boundary. */
@Singleton
public final class SessionOperationTracker {
    private final Object lock = new Object();
    private final CompositeDisposable operations = new CompositeDisposable();
    private boolean active = true;
    private long generation;

    @Inject
    public SessionOperationTracker() { }

    /** Prevents new work and disposes requests accepted by the previous session. */
    public long invalidate() {
        synchronized (lock) {
            active = false;
            generation++;
            operations.clear();
            return generation;
        }
    }

    /** The boundary a request started under; compare with {@link #invalidateIfCurrent}. */
    public long currentGeneration() {
        synchronized (lock) {
            return generation;
        }
    }

    /** Like {@link #invalidate()}, but refuses (returns -1) when a newer boundary already passed. */
    public long invalidateIfCurrent(long expectedGeneration) {
        synchronized (lock) {
            if (generation != expectedGeneration) return -1L;
            return invalidate();
        }
    }

    /** Enables work only if no newer session boundary has superseded this login. */
    public boolean activate(long expectedGeneration) {
        synchronized (lock) {
            if (generation != expectedGeneration) return false;
            active = true;
            return true;
        }
    }

    public boolean isActive() {
        synchronized (lock) {
            return active;
        }
    }

    @NonNull
    public <T> Single<T> track(@NonNull Single<T> source) {
        return Single.create(emitter -> {
            SerialDisposable operation = new SerialDisposable();
            emitter.setCancellable(() -> {
                operations.delete(operation);
                operation.dispose();
            });
            synchronized (lock) {
                if (!active) {
                    emitter.onError(new CancellationException("No active account session"));
                    return;
                }
                operations.add(operation);
                Disposable upstream = source.subscribe(value -> {
                    operations.delete(operation);
                    emitter.onSuccess(value);
                }, error -> {
                    operations.delete(operation);
                    emitter.onError(error);
                });
                operation.set(upstream);
            }
        });
    }

    @NonNull
    public Completable track(@NonNull Completable source) {
        return Completable.create(emitter -> {
            SerialDisposable operation = new SerialDisposable();
            emitter.setCancellable(() -> {
                operations.delete(operation);
                operation.dispose();
            });
            synchronized (lock) {
                if (!active) {
                    emitter.onComplete();
                    return;
                }
                operations.add(operation);
                Disposable upstream = source.subscribe(() -> {
                    operations.delete(operation);
                    emitter.onComplete();
                }, error -> {
                    operations.delete(operation);
                    emitter.onError(error);
                });
                operation.set(upstream);
            }
        });
    }
}
