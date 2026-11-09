package com.nexachat.data;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.WorkManager;
import com.nexachat.data.OutboxSyncWorker;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import dagger.hilt.android.qualifiers.ApplicationContext;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class OfflineOutboxScheduler {
    private final Context appContext;

    @Inject
    public OfflineOutboxScheduler(@ApplicationContext @NonNull Context appContext) {
        this.appContext = appContext.getApplicationContext();
    }

    public void schedule() {
        OutboxSyncWorker.enqueue(appContext);
    }

    /** Stops queued or running account-scoped outbox work before Room is cleared. */
    @NonNull
    public Completable cancelPending() {
        return Completable.fromAction(() -> {
            try {
                WorkManager.getInstance(appContext)
                        .cancelUniqueWork(OutboxSyncWorker.WORK_NAME)
                        .getResult()
                        .get(10, TimeUnit.SECONDS);
            } catch (IllegalStateException unavailableInLocalTest) {
                // WorkManager is not initialized in local JVM tests.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while stopping offline outbox", interrupted);
            } catch (ExecutionException | TimeoutException failure) {
                throw new IllegalStateException("Could not stop offline outbox", failure);
            }
        }).subscribeOn(Schedulers.io());
    }
}
