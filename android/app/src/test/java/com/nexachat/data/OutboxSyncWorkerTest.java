package com.nexachat.data;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import androidx.work.ListenableWorker;
import androidx.work.WorkerParameters;
import com.nexachat.core.database.OfflineOutboxDao;
import com.nexachat.core.database.OfflineOutboxEntity;
import com.nexachat.core.network.ApiResult;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.observers.TestObserver;
import java.io.IOException;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/** Worker wiring: flush-then-drain, lease gating and unavailable-dependency handling. */
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
        OfflineOutboxDao offlineOutboxDao = emptyDao();

        OutboxSyncWorker worker = new OutboxSyncWorker(
                context, workerParameters, messageRepository, offlineOutboxDao);
        TestObserver<ListenableWorker.Result> observer = worker.createWork().test();

        observer.awaitCount(1);
        observer.assertComplete();
        assertEquals(ListenableWorker.Result.success(), observer.values().get(0));
    }

    @Test
    public void createWorkReturnsRetryWhenFlushFails() {
        when(messageRepository.flushOutbox()).thenReturn(Completable.error(new IOException("offline")));
        OfflineOutboxDao offlineOutboxDao = emptyDao();

        OutboxSyncWorker worker = new OutboxSyncWorker(
                context, workerParameters, messageRepository, offlineOutboxDao);
        TestObserver<ListenableWorker.Result> observer = worker.createWork().test();

        observer.awaitCount(1);
        observer.assertComplete();
        assertEquals(ListenableWorker.Result.retry(), observer.values().get(0));
    }

    @Test
    public void createWorkWithoutDaoReturnsRetry() {
        OutboxSyncWorker worker = new OutboxSyncWorker(context, workerParameters, messageRepository);
        TestObserver<ListenableWorker.Result> observer = worker.createWork().test();

        observer.awaitCount(1);
        observer.assertComplete();
        assertEquals(ListenableWorker.Result.retry(), observer.values().get(0));
    }

    @Test
    public void createWorkDrainsOfflineOutboxAndMarksSent() {
        OfflineOutboxEntity entity = new OfflineOutboxEntity(
                "item-1", "idemp-1", "REACTION_ADD", "{}", "PENDING", 0, 1000L, 0L);
        OfflineOutboxDao offlineOutboxDao = emptyDao();
        when(offlineOutboxDao.getClaimableItems(anyLong())).thenReturn(Single.just(List.of(entity)));
        when(offlineOutboxDao.claimLease(eq("item-1"), anyLong(), anyLong()))
                .thenReturn(Single.just(1));
        when(offlineOutboxDao.findById("item-1")).thenReturn(Maybe.just(entity));
        when(offlineOutboxDao.markSent(eq("item-1"), anyLong())).thenReturn(Completable.complete());
        when(messageRepository.flushOutbox()).thenReturn(Completable.complete());
        when(messageRepository.dispatchOfflineAction(entity))
                .thenReturn(Single.just(new ApiResult.Success<>(null)));

        OutboxSyncWorker worker = new OutboxSyncWorker(
                context, workerParameters, messageRepository, offlineOutboxDao);
        TestObserver<ListenableWorker.Result> observer = worker.createWork().test();

        observer.awaitCount(1);
        observer.assertComplete();
        assertEquals(ListenableWorker.Result.success(), observer.values().get(0));

        verify(offlineOutboxDao).claimLease(eq("item-1"), anyLong(), anyLong());
        verify(messageRepository).dispatchOfflineAction(entity);
        verify(offlineOutboxDao).markSent(eq("item-1"), anyLong());
    }

    @Test
    public void drainOfflineOutboxSkipsWhenClaimReturnsZero() {
        OfflineOutboxEntity entity = new OfflineOutboxEntity(
                "item-2", "idemp-2", "REACTION_ADD", "{}", "IN_PROGRESS", 0, 1000L, 500L);
        OfflineOutboxDao offlineOutboxDao = emptyDao();
        when(offlineOutboxDao.getClaimableItems(anyLong())).thenReturn(Single.just(List.of(entity)));
        when(offlineOutboxDao.claimLease(eq("item-2"), anyLong(), anyLong()))
                .thenReturn(Single.just(0)); // Already locked by another worker
        when(messageRepository.flushOutbox()).thenReturn(Completable.complete());

        OutboxSyncWorker worker = new OutboxSyncWorker(
                context, workerParameters, messageRepository, offlineOutboxDao);
        TestObserver<Void> observer = worker.drainOfflineOutbox().test();

        observer.awaitCount(1);
        observer.assertComplete();

        verify(offlineOutboxDao, never()).markSent(anyString(), anyLong());
        verify(messageRepository, never()).dispatchOfflineAction(any());
    }

    @Test
    public void drainOfflineOutboxRequiresDispatcher() {
        OfflineOutboxDao offlineOutboxDao = emptyDao();
        OutboxSyncWorker worker = new OutboxSyncWorker(
                context, workerParameters, null, offlineOutboxDao);
        TestObserver<Void> observer = worker.drainOfflineOutbox().test();

        observer.awaitCount(1);
        observer.assertNotComplete();
        observer.assertError(IllegalStateException.class);
    }

    private static OfflineOutboxDao emptyDao() {
        OfflineOutboxDao dao = mock(OfflineOutboxDao.class);
        when(dao.getClaimableItems(anyLong())).thenReturn(Single.just(List.of()));
        return dao;
    }
}
