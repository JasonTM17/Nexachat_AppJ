package com.nexachat.data;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkerParameters;
import androidx.work.rxjava3.RxWorker;
import com.nexachat.core.common.ApiError;
import com.nexachat.core.database.OfflineOutboxDao;
import com.nexachat.core.database.OfflineOutboxEntity;
import com.nexachat.core.database.OutboxState;
import com.nexachat.core.network.ApiResult;
import dagger.hilt.EntryPoint;
import dagger.hilt.InstallIn;
import dagger.hilt.android.EntryPointAccessors;
import dagger.hilt.components.SingletonComponent;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** WorkManager worker that dispatches durable reaction and pin actions. */
public final class OutboxSyncWorker extends RxWorker {
    public static final String WORK_NAME = "com.nexachat.outbox.sync";
    public static final int MAX_ATTEMPTS = 5;
    public static final long BACKOFF_INITIAL_DELAY_MS = 10_000L;

    @EntryPoint
    @InstallIn(SingletonComponent.class)
    public interface OutboxSyncWorkerEntryPoint {
        MessageRepository messageRepository();
        OfflineOutboxDao offlineOutboxDao();
    }

    private final MessageRepository messageRepository;
    private final OfflineOutboxDao offlineOutboxDao;

    public OutboxSyncWorker(
            @NonNull Context appContext,
            @NonNull WorkerParameters workerParams) {
        this(appContext, workerParams, resolveRepository(appContext), resolveOfflineOutboxDao(appContext));
    }

    @VisibleForTesting
    public OutboxSyncWorker(
            @NonNull Context appContext,
            @NonNull WorkerParameters workerParams,
            MessageRepository messageRepository) {
        this(appContext, workerParams, messageRepository, null);
    }

    @VisibleForTesting
    public OutboxSyncWorker(
            @NonNull Context appContext,
            @NonNull WorkerParameters workerParams,
            MessageRepository messageRepository,
            OfflineOutboxDao offlineOutboxDao) {
        super(appContext, workerParams);
        this.messageRepository = messageRepository;
        this.offlineOutboxDao = offlineOutboxDao;
    }

    private static MessageRepository resolveRepository(Context context) {
        try {
            OutboxSyncWorkerEntryPoint entryPoint = EntryPointAccessors.fromApplication(
                    context.getApplicationContext(), OutboxSyncWorkerEntryPoint.class);
            return entryPoint.messageRepository();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static OfflineOutboxDao resolveOfflineOutboxDao(Context context) {
        try {
            OutboxSyncWorkerEntryPoint entryPoint = EntryPointAccessors.fromApplication(
                    context.getApplicationContext(), OutboxSyncWorkerEntryPoint.class);
            return entryPoint.offlineOutboxDao();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @NonNull
    @Override
    public Single<Result> createWork() {
        if (offlineOutboxDao == null) return Single.just(Result.retry());
        Completable flushMessages = messageRepository == null
                ? Completable.complete() : messageRepository.flushOutbox();
        return flushMessages.andThen(drainOfflineOutbox())
                .toSingleDefault(Result.success())
                .onErrorReturnItem(Result.retry());
    }

    public Completable drainOfflineOutbox() {
        if (offlineOutboxDao == null) return Completable.error(
                new IllegalStateException("offline outbox database is unavailable"));
        if (messageRepository == null) return Completable.error(
                new IllegalStateException("offline outbox dispatcher is unavailable"));
        long now = System.currentTimeMillis();
        long staleThreshold = now - 60_000L;
        return offlineOutboxDao.getClaimableItems(staleThreshold)
                .flatMapCompletable(items -> {
                    if (items.isEmpty()) return Completable.complete();
                    return Flowable.fromIterable(items)
                            .concatMapCompletable(item -> processOfflineItem(item, staleThreshold));
                });
    }

    private Completable processOfflineItem(OfflineOutboxEntity item, long staleThreshold) {
        long now = System.currentTimeMillis();
        return offlineOutboxDao.claimLease(item.getId(), now, staleThreshold)
                .flatMapCompletable(claimed -> {
                    if (claimed == 0) return Completable.complete();
                    return offlineOutboxDao.findById(item.getId())
                            .switchIfEmpty(Single.error(new IOException("claimed outbox row disappeared")))
                            .flatMapCompletable(current -> messageRepository.dispatchOfflineAction(current)
                                    .onErrorReturn(error -> new ApiResult.NetworkError<>(
                                            new IOException("offline outbox dispatch failed", error)))
                                    .flatMapCompletable(result -> handleResult(current, result)));
                });
    }

    private Completable handleResult(OfflineOutboxEntity item, ApiResult<?> result) {
        if (result instanceof ApiResult.Success<?>) {
            return offlineOutboxDao.markSent(item.getId(), System.currentTimeMillis());
        }
        if (result instanceof ApiResult.NetworkError<?>) {
            return retryOrFail(item, new ApiError.Network());
        }
        ApiResult.HttpError<?> http = (ApiResult.HttpError<?>) result;
        if (isRetryable(http.getStatus())) return retryOrFail(item, ApiError.from(
                http.getStatus(), http.getErrorCode()));
        return messageRepository.failOfflineAction(item,
                ApiError.from(http.getStatus(), http.getErrorCode()));
    }

    private Completable retryOrFail(OfflineOutboxEntity item, ApiError error) {
        int attempts = item.getRetryCount() + 1;
        String payload;
        try {
            payload = messageRepository.payloadWithAttemptCount(item, attempts);
        } catch (IOException invalidPayload) {
            return messageRepository.failOfflineAction(item, new ApiError.Unknown("OUTBOX_PAYLOAD_INVALID"));
        }
        if (attempts >= MAX_ATTEMPTS) return messageRepository.failOfflineAction(item, error);
        return offlineOutboxDao.releaseLease(item.getId(), attempts, payload)
                .andThen(Completable.error(new RetryDrainException()));
    }

    private static boolean isRetryable(int status) {
        return status == 429 || status >= 500;
    }

    @VisibleForTesting
    public static OneTimeWorkRequest newRequest() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        return new OneTimeWorkRequest.Builder(OutboxSyncWorker.class)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,
                        BACKOFF_INITIAL_DELAY_MS, TimeUnit.MILLISECONDS)
                .build();
    }

    /** Enqueues constrained work; WorkManager resumes it when connectivity returns. */
    public static void enqueue(@NonNull Context context) {
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME, ExistingWorkPolicy.KEEP, newRequest());
        } catch (IllegalStateException ignored) {
            // WorkManager is unavailable in local JVM tests.
        }
    }

    private static final class RetryDrainException extends RuntimeException {}
}
