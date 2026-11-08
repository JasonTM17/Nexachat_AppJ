package com.nexachat.notification;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.work.WorkerParameters;
import androidx.work.rxjava3.RxWorker;
import dagger.hilt.EntryPoint;
import dagger.hilt.InstallIn;
import dagger.hilt.android.EntryPointAccessors;
import dagger.hilt.components.SingletonComponent;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/** WorkManager boundary for token registration and logout unregister retries. */
public final class DeviceTokenSyncWorker extends RxWorker {

    @EntryPoint
    @InstallIn(SingletonComponent.class)
    public interface WorkerEntryPoint {
        DeviceTokenSyncExecutor deviceTokenSyncExecutor();
    }

    private final DeviceTokenSyncExecutor executor;

    public DeviceTokenSyncWorker(
            @NonNull Context appContext,
            @NonNull WorkerParameters workerParams) {
        this(appContext, workerParams, resolveExecutor(appContext));
    }

    @VisibleForTesting
    public DeviceTokenSyncWorker(
            @NonNull Context appContext,
            @NonNull WorkerParameters workerParams,
            DeviceTokenSyncExecutor executor) {
        super(appContext, workerParams);
        this.executor = executor;
    }

    private static DeviceTokenSyncExecutor resolveExecutor(Context context) {
        try {
            WorkerEntryPoint entryPoint = EntryPointAccessors.fromApplication(
                    context.getApplicationContext(), WorkerEntryPoint.class);
            return entryPoint.deviceTokenSyncExecutor();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    @NonNull
    @Override
    public Single<Result> createWork() {
        if (executor == null) {
            // A missing Hilt graph is transient: retry within budget instead of
            // reporting success for work that never ran.
            DeviceTokenDiagnostics.warn(DeviceTokenDiagnostics.EXECUTOR_MISSING);
            return Single.just(DeviceTokenRetryPolicy.shouldRetry(getRunAttemptCount())
                    ? Result.retry() : Result.failure());
        }
        return Single.fromCallable(() -> executor.execute(getRunAttemptCount()))
                .subscribeOn(Schedulers.io())
                .map(outcome -> {
                    if (outcome == DeviceTokenSyncExecutor.Outcome.SUCCESS) {
                        return Result.success();
                    }
                    if (outcome == DeviceTokenSyncExecutor.Outcome.RETRY) {
                        return Result.retry();
                    }
                    return Result.failure();
                })
                .onErrorReturn(error -> {
                    DeviceTokenDiagnostics.warnError(error.getClass());
                    return DeviceTokenRetryPolicy.shouldRetry(getRunAttemptCount())
                            ? Result.retry() : Result.failure();
                });
    }
}
