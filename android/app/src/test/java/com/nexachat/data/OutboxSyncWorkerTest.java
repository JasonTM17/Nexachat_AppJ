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
}
