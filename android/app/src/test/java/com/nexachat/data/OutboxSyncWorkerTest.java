package com.nexachat.data;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        when(messageRepository.flushOutboxForWorker()).thenReturn(Completable.complete());
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
        when(messageRepository.flushOutboxForWorker()).thenReturn(Completable.error(new IOException("offline")));
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
        when(offlineOutboxDao.getClaimableItems(anyLong()))
                .thenReturn(Single.just(List.of(entity)), Single.just(List.of()));
        when(offlineOutboxDao.claimLease(eq("item-1"), anyLong(), anyLong()))
                .thenReturn(Single.just(1));
        when(offlineOutboxDao.findById("item-1")).thenReturn(Maybe.just(entity));
        when(offlineOutboxDao.markSent(eq("item-1"), anyLong())).thenReturn(Completable.complete());
        when(messageRepository.flushOutboxForWorker()).thenReturn(Completable.complete());
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
    public void createWorkRechecksQueueForItemsInsertedDuringDrain() {
        OfflineOutboxEntity first = new OfflineOutboxEntity(
                "item-1", "idemp-1", "REACTION_ADD", "{}", "PENDING", 0, 1000L, 0L);
        OfflineOutboxEntity insertedDuringDrain = new OfflineOutboxEntity(
                "item-2", "idemp-2", "PIN_ADD", "{}", "PENDING", 0, 1001L, 0L);
        FakeOfflineOutboxDao fakeDao = new FakeOfflineOutboxDao(first);
        List<String> dispatched = new ArrayList<>();
        when(messageRepository.flushOutboxForWorker()).thenReturn(Completable.complete());
        when(messageRepository.dispatchOfflineAction(any())).thenAnswer(invocation -> {
            OfflineOutboxEntity item = invocation.getArgument(0);
            dispatched.add(item.getId());
            if ("item-1".equals(item.getId())) {
                fakeDao.insert(insertedDuringDrain);
            }
            return Single.just(new ApiResult.Success<>(null));
        });

        OutboxSyncWorker worker = new OutboxSyncWorker(
                context, workerParameters, messageRepository, fakeDao.asDao());
        TestObserver<ListenableWorker.Result> observer = worker.createWork().test();

        observer.awaitCount(1);
        observer.assertComplete();
        assertEquals(ListenableWorker.Result.success(), observer.values().get(0));
        assertEquals(List.of("item-1", "item-2"), dispatched);
        assertEquals("SENT", fakeDao.item("item-1").getState());
        assertEquals("SENT", fakeDao.item("item-2").getState());
        verify(messageRepository, times(2)).dispatchOfflineAction(any());
    }

    @Test
    public void drainOfflineOutboxSkipsWhenClaimReturnsZero() {
        OfflineOutboxEntity entity = new OfflineOutboxEntity(
                "item-2", "idemp-2", "REACTION_ADD", "{}", "IN_PROGRESS", 0, 1000L, 500L);
        OfflineOutboxDao offlineOutboxDao = emptyDao();
        when(offlineOutboxDao.getClaimableItems(anyLong())).thenReturn(Single.just(List.of(entity)));
        when(offlineOutboxDao.claimLease(eq("item-2"), anyLong(), anyLong()))
                .thenReturn(Single.just(0)); // Already locked by another worker
        when(messageRepository.flushOutboxForWorker()).thenReturn(Completable.complete());

        OutboxSyncWorker worker = new OutboxSyncWorker(
                context, workerParameters, messageRepository, offlineOutboxDao);
        TestObserver<Void> observer = worker.drainOfflineOutbox().test();

        observer.awaitCount(1);
        observer.assertComplete();

        verify(offlineOutboxDao, times(1)).getClaimableItems(anyLong());
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

    private static final class FakeOfflineOutboxDao implements InvocationHandler {
        private final Map<String, OfflineOutboxEntity> items = new LinkedHashMap<>();

        private FakeOfflineOutboxDao(OfflineOutboxEntity initialItem) {
            insert(initialItem);
        }

        private void insert(OfflineOutboxEntity item) {
            items.put(item.getId(), item);
        }

        private OfflineOutboxEntity item(String id) {
            return items.get(id);
        }

        private OfflineOutboxDao asDao() {
            return (OfflineOutboxDao) Proxy.newProxyInstance(
                    OfflineOutboxDao.class.getClassLoader(),
                    new Class<?>[] {OfflineOutboxDao.class},
                    this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getClaimableItems" -> Single.just(claimableItems());
                case "claimLease" -> claim((String) args[0], (long) args[1]);
                case "findById" -> find((String) args[0]);
                case "markSent" -> markSent((String) args[0], (long) args[1]);
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }

        private List<OfflineOutboxEntity> claimableItems() {
            List<OfflineOutboxEntity> claimable = new ArrayList<>();
            for (OfflineOutboxEntity item : items.values()) {
                if ("PENDING".equals(item.getState()) || "SENDING".equals(item.getState())) {
                    claimable.add(item);
                }
            }
            return claimable;
        }

        private Single<Integer> claim(String id, long now) {
            OfflineOutboxEntity item = items.get(id);
            if (item == null || !("PENDING".equals(item.getState())
                    || "SENDING".equals(item.getState()))) {
                return Single.just(0);
            }
            items.put(id, item.withStateAndAttempt("IN_PROGRESS", item.getRetryCount(), now));
            return Single.just(1);
        }

        private Maybe<OfflineOutboxEntity> find(String id) {
            OfflineOutboxEntity item = items.get(id);
            return item == null ? Maybe.empty() : Maybe.just(item);
        }

        private Completable markSent(String id, long now) {
            OfflineOutboxEntity item = items.get(id);
            if (item != null) {
                items.put(id, item.withStateAndAttempt("SENT", item.getRetryCount(), now));
            }
            return Completable.complete();
        }
    }
}
