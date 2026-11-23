package com.nexachat.scheduled;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScheduledMessageSchedulerTest {

    private static final Instant START = Instant.parse("2026-01-02T03:04:05Z");

    @Test
    void scheduledMessageSchedulerDoesNotUseAFixedPeriodicSchedule() {
        assertThat(Arrays.stream(ScheduledMessageScheduler.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Scheduled.class))
                .map(Method::getName))
                .isEmpty();
    }

    @Test
    void anEmptyQueueSleepsForThePollInterval() {
        Harness harness = new Harness(Duration.ofSeconds(15), true);
        harness.scheduler().start();

        assertThat(harness.scheduledAt()).isEqualTo(START.plus(Duration.ofSeconds(15)));
        assertThat(harness.wakeQueries()).hasValue(1);
        assertThat(harness.claimQueries()).hasValue(0);

        harness.clock().advance(Duration.ofSeconds(15));
        harness.runScheduledTask();
        assertThat(harness.claimQueries()).hasValue(1);
        assertThat(harness.scheduledAt()).isEqualTo(START.plus(Duration.ofSeconds(30)));
        harness.scheduler().stop();
    }

    @Test
    void aDueRowIsScheduledForItsDueInstantNotThePollInterval() {
        Harness harness = new Harness(Duration.ofSeconds(15), true);
        harness.nextWakeDelayMillis().set(Duration.ofSeconds(7).toMillis());

        harness.scheduler().start();

        assertThat(harness.scheduledAt()).isEqualTo(START.plus(Duration.ofSeconds(7)));
        harness.clock().advance(Duration.ofSeconds(7));
        harness.runScheduledTask();
        assertThat(harness.claimQueries()).hasValue(1);
        harness.scheduler().stop();
    }

    @Test
    void aCommittedChangeRearmsAnEarlierTimerAndCancelsTheOldOne() {
        Harness harness = new Harness(Duration.ofSeconds(15), true);
        harness.nextWakeDelayMillis().set(Duration.ofMinutes(45).toMillis());
        harness.scheduler().start();
        ScheduledFuture<?> oldTimer = harness.futures().get(0);

        harness.nextWakeDelayMillis().set(Duration.ofSeconds(10).toMillis());
        harness.scheduler().onScheduleChanged(new ScheduledMessageChangedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "PENDING", null, Instant.now(), null));

        assertThat(harness.scheduledAt()).isEqualTo(START.plus(Duration.ofSeconds(10)));
        verify(oldTimer).cancel(false);
        assertThat(harness.futures()).hasSize(2);
        harness.scheduler().stop();
    }

    @Test
    void eventListenerRunsAfterCommitOnly() throws Exception {
        Method listener = ScheduledMessageScheduler.class.getMethod(
                "onScheduleChanged", ScheduledMessageChangedEvent.class);
        TransactionalEventListener annotation =
                listener.getAnnotation(TransactionalEventListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(annotation.fallbackExecution()).isFalse();
    }

    @Test
    void aRunAlreadyInProgressIsNotStartedAgain() throws Exception {
        Harness harness = new Harness(Duration.ofSeconds(15), true);
        CountDownLatch runEntered = new CountDownLatch(1);
        CountDownLatch finishRun = new CountDownLatch(1);
        doAnswer(invocation -> {
            harness.claimQueries().incrementAndGet();
            runEntered.countDown();
            if (!finishRun.await(900, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("test did not release the scheduled run");
            }
            return List.of();
        }).when(harness.claimService()).claimDue(anyLong(), any(UUID.class), anyInt());

        harness.scheduler().start();
        Runnable capturedTask = harness.scheduledTask();
        Thread worker = new Thread(capturedTask, "scheduled-message-scheduler-test");
        worker.start();
        assertThat(runEntered.await(900, TimeUnit.MILLISECONDS)).isTrue();

        capturedTask.run();
        assertThat(harness.claimQueries()).hasValue(1);

        finishRun.countDown();
        worker.join(900);
        assertThat(worker.isAlive()).isFalse();
        assertThat(harness.claimQueries()).hasValue(1);
        harness.scheduler().stop();
    }

    @Test
    void disabledSchedulerDoesNotQueryOrScheduleAtStartupOrOnEvents() {
        Harness harness = new Harness(Duration.ofSeconds(15), false);

        harness.scheduler().start();
        harness.scheduler().onScheduleChanged(new ScheduledMessageChangedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "PENDING", null, Instant.now(), null));

        assertThat(harness.wakeQueries()).hasValue(0);
        assertThat(harness.futures()).isEmpty();
        verify(harness.taskScheduler(), never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void shutdownCancelsItsTimerAndMakesAnAlreadyDequeuedTaskInert() {
        Harness harness = new Harness(Duration.ofSeconds(15), true);
        harness.scheduler().start();
        Runnable capturedTask = harness.scheduledTask();
        ScheduledFuture<?> pending = harness.futures().get(0);

        harness.scheduler().stop();
        capturedTask.run();

        verify(pending).cancel(false);
        assertThat(harness.claimQueries()).hasValue(0);
        assertThat(harness.wakeQueries()).hasValue(1);
    }

    @Test
    void runOnceResolvesEveryClaimedRowAndDrainsUntilEmpty() {
        Harness harness = new Harness(Duration.ofSeconds(30), true);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ScheduledMessageDispatch firstDispatch = dispatch(first);
        ScheduledMessageDispatch secondDispatch = dispatch(second);

        when(harness.claimService().claimDue(anyLong(), any(UUID.class), anyInt()))
                .thenReturn(List.of(first, second))
                .thenReturn(List.of());
        when(harness.claimService().findDispatch(eq(first), any(UUID.class)))
                .thenReturn(java.util.Optional.of(firstDispatch));
        when(harness.claimService().findDispatch(eq(second), any(UUID.class)))
                .thenReturn(java.util.Optional.of(secondDispatch));
        when(harness.dispatcher().process(firstDispatch)).thenReturn(DispatchOutcome.SENT);
        when(harness.dispatcher().process(secondDispatch)).thenReturn(DispatchOutcome.RETRY);

        assertThat(harness.scheduler().runOnce()).isEqualTo(2);
        verify(harness.claimService(), times(2))
                .claimDue(anyLong(), any(UUID.class), anyInt());
        verify(harness.dispatcher()).process(firstDispatch);
        verify(harness.dispatcher()).process(secondDispatch);
        harness.scheduler().stop();
    }

    @Test
    void aRowWhoseClaimWasLostBetweenClaimAndSnapshotDoesNotDispatch() {
        Harness harness = new Harness(Duration.ofSeconds(30), true);
        UUID orphan = UUID.randomUUID();
        when(harness.claimService().claimDue(anyLong(), any(UUID.class), anyInt()))
                .thenReturn(List.of(orphan))
                .thenReturn(List.of());
        when(harness.claimService().findDispatch(eq(orphan), any(UUID.class)))
                .thenReturn(java.util.Optional.empty());

        assertThat(harness.scheduler().runOnce()).isEqualTo(1);
        verify(harness.dispatcher(), never()).process(any());
        harness.scheduler().stop();
    }

    private static ScheduledMessageDispatch dispatch(UUID id) {
        return new ScheduledMessageDispatch(id, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "body", Instant.now(), 0, UUID.randomUUID());
    }

    private static final class Harness {
        private final ScheduledMessageClaimService claimService =
                mock(ScheduledMessageClaimService.class);
        private final ScheduledMessageDispatcher dispatcher =
                mock(ScheduledMessageDispatcher.class);
        private final ScheduledMessageRepository scheduledMessageRepository =
                mock(ScheduledMessageRepository.class);
        private final TaskScheduler taskScheduler = mock(TaskScheduler.class);
        private final AsyncTaskExecutor dispatchExecutor = new InlineExecutor();
        private final MutableClock clock;
        private final AtomicInteger wakeQueries = new AtomicInteger();
        private final AtomicInteger claimQueries = new AtomicInteger();
        private final AtomicReference<Long> nextWakeDelayMillis = new AtomicReference<>();
        private final AtomicReference<Runnable> scheduledTask = new AtomicReference<>();
        private final AtomicReference<Instant> scheduledAt = new AtomicReference<>();
        private final java.util.ArrayList<ScheduledFuture<?>> futures =
                new java.util.ArrayList<>();
        private final ScheduledMessageScheduler scheduler;

        private Harness(Duration pollInterval, boolean enabled) {
            clock = new MutableClock(START);
            when(scheduledMessageRepository.findNextWakeDelayMillis(anyLong()))
                    .thenAnswer(invocation -> {
                        wakeQueries.incrementAndGet();
                        return nextWakeDelayMillis.get();
                    });
            when(claimService.claimDue(anyLong(), any(UUID.class), anyInt()))
                    .thenAnswer(invocation -> {
                        claimQueries.incrementAndGet();
                        return List.of();
                    });
            when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                    .thenAnswer(invocation -> {
                        scheduledTask.set(invocation.getArgument(0));
                        scheduledAt.set(invocation.getArgument(1));
                        ScheduledFuture<?> future = mock(ScheduledFuture.class);
                        futures.add(future);
                        return future;
                    });
            scheduler = new ScheduledMessageScheduler(claimService, dispatcher,
                    scheduledMessageRepository, taskScheduler, dispatchExecutor,
                    20, 60, enabled, pollInterval.toMillis(), 30_000, clock);
        }

        private ScheduledMessageScheduler scheduler() {
            return scheduler;
        }

        private MutableClock clock() {
            return clock;
        }

        private AtomicInteger wakeQueries() {
            return wakeQueries;
        }

        private AtomicInteger claimQueries() {
            return claimQueries;
        }

        private AtomicReference<Long> nextWakeDelayMillis() {
            return nextWakeDelayMillis;
        }

        private TaskScheduler taskScheduler() {
            return taskScheduler;
        }

        private List<ScheduledFuture<?>> futures() {
            return futures;
        }

        private Runnable scheduledTask() {
            return scheduledTask.get();
        }

        private Instant scheduledAt() {
            return scheduledAt.get();
        }

        private ScheduledMessageClaimService claimService() {
            return claimService;
        }

        private ScheduledMessageDispatcher dispatcher() {
            return dispatcher;
        }

        private void runScheduledTask() {
            scheduledTask.get().run();
        }
    }

    /** Runs submitted work on the caller thread so outcomes are deterministic. */
    private static final class InlineExecutor implements AsyncTaskExecutor {
        @Override
        public void execute(Runnable task) {
            task.run();
        }

        @Override
        public void execute(Runnable task, long startTimeout) {
            task.run();
        }

        @Override
        public Future<?> submit(Runnable task) {
            task.run();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            CompletableFuture<T> future = new CompletableFuture<>();
            try {
                future.complete(task.call());
            } catch (Exception failure) {
                future.completeExceptionally(failure);
            }
            return future;
        }

        @Override
        public CompletableFuture<Void> submitCompletable(Runnable task) {
            task.run();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <T> CompletableFuture<T> submitCompletable(Callable<T> task) {
            return (CompletableFuture<T>) submit(task);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
