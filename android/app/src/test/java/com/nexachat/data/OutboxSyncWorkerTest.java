package com.nexachat.data;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import androidx.work.ListenableWorker;
import androidx.work.WorkerParameters;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.observers.TestObserver;
import java.io.IOException;
import org.junit.Before;
import org.junit.Test;

public final class OutboxSyncWorkerTest {

    private Context context;
    private WorkerParameters workerParameters;
    private MessageRepository messageRepository;

    @Before
    public void setUp() {
        context = mock(Context.class);
        workerParameters = mock(WorkerParameters.class);
        messageRepository = mock(MessageRepository.class);
    }

    @Test
    public void createWorkFlushesOutboxAndReturnsSuccessOnComplete() {
        when(messageRepository.flushOutbox()).thenReturn(Completable.complete());

        OutboxSyncWorker worker = new OutboxSyncWorker(context, workerParameters, messageRepository);
        TestObserver<ListenableWorker.Result> observer = worker.createWork().test();

        observer.awaitCount(1);
        observer.assertComplete();
        assertEquals(ListenableWorker.Result.success(), observer.values().get(0));
    }

    @Test
    public void createWorkReturnsRetryOnError() {
        when(messageRepository.flushOutbox()).thenReturn(Completable.error(new IOException("Network unavailable")));

        OutboxSyncWorker worker = new OutboxSyncWorker(context, workerParameters, messageRepository);
        TestObserver<ListenableWorker.Result> observer = worker.createWork().test();

        observer.awaitCount(1);
        observer.assertComplete();
        assertEquals(ListenableWorker.Result.retry(), observer.values().get(0));
    }

    @Test
    public void createWorkWithNullRepositoryReturnsSuccess() {
        OutboxSyncWorker worker = new OutboxSyncWorker(context, workerParameters, null);
        TestObserver<ListenableWorker.Result> observer = worker.createWork().test();

        observer.awaitCount(1);
        observer.assertComplete();
        assertEquals(ListenableWorker.Result.success(), observer.values().get(0));
    }

    @Test
    public void createWorkDrainsOfflineOutboxWithLeaseLock() {
        com.nexachat.core.database.OfflineOutboxDao offlineOutboxDao =
                mock(com.nexachat.core.database.OfflineOutboxDao.class);
        com.nexachat.core.database.OfflineOutboxEntity entity =
                new com.nexachat.core.database.OfflineOutboxEntity(
                        "item-1", "idemp-1", "SEND_MESSAGE", "{}", "PENDING", 0, 1000L, 0L);

        when(messageRepository.flushOutbox()).thenReturn(Completable.complete());
        when(offlineOutboxDao.getClaimableItems(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(io.reactivex.rxjava3.core.Single.just(java.util.List.of(entity)));
        when(offlineOutboxDao.claimLease(
                org.mockito.ArgumentMatchers.eq("item-1"),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(io.reactivex.rxjava3.core.Single.just(1));
        when(offlineOutboxDao.markSent(
                org.mockito.ArgumentMatchers.eq("item-1"),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(Completable.complete());

        OutboxSyncWorker worker = new OutboxSyncWorker(
                context, workerParameters, messageRepository, offlineOutboxDao);
        TestObserver<ListenableWorker.Result> observer = worker.createWork().test();

        observer.awaitCount(1);
        observer.assertComplete();
        assertEquals(ListenableWorker.Result.success(), observer.values().get(0));

        org.mockito.Mockito.verify(offlineOutboxDao).claimLease(
                org.mockito.ArgumentMatchers.eq("item-1"),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.verify(offlineOutboxDao).markSent(
                org.mockito.ArgumentMatchers.eq("item-1"),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    public void drainOfflineOutboxSkipsWhenClaimReturnsZero() {
        com.nexachat.core.database.OfflineOutboxDao offlineOutboxDao =
                mock(com.nexachat.core.database.OfflineOutboxDao.class);
        com.nexachat.core.database.OfflineOutboxEntity entity =
                new com.nexachat.core.database.OfflineOutboxEntity(
                        "item-2", "idemp-2", "SEND_MESSAGE", "{}", "IN_PROGRESS", 0, 1000L, 500L);

        when(offlineOutboxDao.getClaimableItems(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(io.reactivex.rxjava3.core.Single.just(java.util.List.of(entity)));
        when(offlineOutboxDao.claimLease(
                org.mockito.ArgumentMatchers.eq("item-2"),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(io.reactivex.rxjava3.core.Single.just(0)); // Already locked by another worker

        OutboxSyncWorker worker = new OutboxSyncWorker(
                context, workerParameters, null, offlineOutboxDao);
        TestObserver<Void> observer = worker.drainOfflineOutbox().test();

        observer.awaitCount(1);
        observer.assertComplete();

        org.mockito.Mockito.verify(offlineOutboxDao, org.mockito.Mockito.never()).markSent(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());
    }
}
