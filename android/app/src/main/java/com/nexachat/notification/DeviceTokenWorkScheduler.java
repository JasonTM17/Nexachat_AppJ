package com.nexachat.notification;

import android.content.Context;
import androidx.annotation.NonNull;
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

/** Schedules one idempotent registration/unregistration reconciliation job. */
@Singleton
public final class DeviceTokenWorkScheduler {
    public static final String WORK_NAME = "com.nexachat.device-token.sync";
    private static final long INITIAL_BACKOFF_SECONDS = 30L;

    private final Context appContext;

    @Inject
    public DeviceTokenWorkScheduler(@ApplicationContext @NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    public void enqueueSync() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(DeviceTokenSyncWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        INITIAL_BACKOFF_SECONDS,
                        TimeUnit.SECONDS)
                .build();
        try {
            WorkManager.getInstance(appContext).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request);
        } catch (IllegalStateException ignored) {
            // Keep token callbacks safe in test and pre-initialization contexts.
        }
    }
}
