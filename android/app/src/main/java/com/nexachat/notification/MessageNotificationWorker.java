package com.nexachat.notification;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.work.Data;
import androidx.work.WorkerParameters;
import androidx.work.rxjava3.RxWorker;
import dagger.hilt.EntryPoint;
import dagger.hilt.InstallIn;
import dagger.hilt.android.EntryPointAccessors;
import dagger.hilt.components.SingletonComponent;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/** Durable WorkManager boundary for NEW_MESSAGE and REMINDER occurrences. */
public final class MessageNotificationWorker extends RxWorker {
    @EntryPoint
    @InstallIn(SingletonComponent.class)
    public interface WorkerEntryPoint {
        NotificationWorkExecutor notificationWorkExecutor();
    }

    @Nullable private final NotificationWorkExecutor executor;

    public MessageNotificationWorker(
            @NonNull Context appContext, @NonNull WorkerParameters workerParams) {
        this(appContext, workerParams, resolveExecutor(appContext));
    }

    @VisibleForTesting
    public MessageNotificationWorker(
            @NonNull Context appContext,
            @NonNull WorkerParameters workerParams,
            @Nullable NotificationWorkExecutor executor) {
        super(appContext, workerParams);
        this.executor = executor;
    }

    @Nullable
    private static NotificationWorkExecutor resolveExecutor(Context context) {
        try {
            WorkerEntryPoint entryPoint = EntryPointAccessors.fromApplication(
                    context.getApplicationContext(), WorkerEntryPoint.class);
            return entryPoint.notificationWorkExecutor();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    @NonNull
    @Override
    public Single<Result> createWork() {
        Data data = getInputData();
        NotificationWorkRequest request = NotificationWorkRequest.fromData(data);
        if (request == null) return Single.just(Result.failure());
        if (executor == null) return Single.just(Result.retry());
        return executor.execute(request)
                .subscribeOn(Schedulers.io())
                .map(outcome -> switch (outcome) {
                    case SUCCESS -> Result.success();
                    case RETRY -> Result.retry();
                    case FAILURE -> Result.failure();
                })
                .onErrorReturn(ignored -> Result.retry());
    }
}
