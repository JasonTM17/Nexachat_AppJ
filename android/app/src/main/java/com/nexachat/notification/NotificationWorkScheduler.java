package com.nexachat.notification;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import dagger.hilt.android.qualifiers.ApplicationContext;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Enqueues account/epoch-scoped notification work durably and coalesces duplicate pushes. */
@Singleton
public final class NotificationWorkScheduler {
    private final Context appContext;

    @Inject
    public NotificationWorkScheduler(@ApplicationContext @NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void enqueue(@NonNull NotificationWorkRequest request) {
        enqueue(WorkManager.getInstance(appContext), request);
    }

    @VisibleForTesting
    static void enqueue(@NonNull WorkManager manager, @NonNull NotificationWorkRequest request) {
        if (!request.isValid()) return;
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(MessageNotificationWorker.class)
                .setInputData(request.toData())
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10L, TimeUnit.SECONDS)
                .build();
        manager.enqueueUniqueWork(request.uniqueWorkName(), ExistingWorkPolicy.KEEP, work);
    }
}
