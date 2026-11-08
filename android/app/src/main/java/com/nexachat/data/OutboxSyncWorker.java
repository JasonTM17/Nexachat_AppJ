package com.nexachat.data;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkerParameters;
import androidx.work.rxjava3.RxWorker;
import com.nexachat.core.database.OfflineOutboxDao;
import dagger.hilt.EntryPoint;
import dagger.hilt.InstallIn;
import dagger.hilt.android.EntryPointAccessors;
import dagger.hilt.components.SingletonComponent;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;

/**
 * WorkManager worker that drains pending offline outbox actions when the device
 * reconnects to the network (NetworkType.CONNECTED constraint).
 */
public final class OutboxSyncWorker extends RxWorker {
    public static final String WORK_NAME = "com.nexachat.outbox.sync";

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
        Completable flushRepo = messageRepository != null
                ? messageRepository.flushOutbox()
                : Completable.complete();

        Completable drainOffline = drainOfflineOutbox();

        return flushRepo.andThen(drainOffline)
                .toSingleDefault(Result.success())
                .onErrorReturnItem(Result.retry());
    }

    public Completable drainOfflineOutbox() {
        if (offlineOutboxDao == null) {
            return Completable.complete();
        }
        long now = System.currentTimeMillis();
        long staleThreshold = now - 60_000L;
        return offlineOutboxDao.getClaimableItems(staleThreshold)
                .flatMapCompletable(items -> {
                    if (items.isEmpty()) {
                        return Completable.complete();
                    }
                    return io.reactivex.rxjava3.core.Flowable.fromIterable(items)
                            .concatMapCompletable(item -> processOfflineItem(item, now, staleThreshold));
                });
    }

    private Completable processOfflineItem(
            com.nexachat.core.database.OfflineOutboxEntity item,
            long now,
            long staleThreshold) {
        return offlineOutboxDao.claimLease(item.getId(), now, staleThreshold)
                .flatMapCompletable(claimed -> {
                    if (claimed == 0) {
                        return Completable.complete();
                    }
                    return offlineOutboxDao.markSent(item.getId(), System.currentTimeMillis());
                });
    }

    /**
     * Enqueues an outbox sync work request with NetworkType.CONNECTED constraint.
     */
    public static void enqueue(@NonNull Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(OutboxSyncWorker.class)
                .setConstraints(constraints)
                .build();

        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    request);
        } catch (IllegalStateException ignored) {
            // In unit test environments without WorkManager initialization
        }
    }
}
