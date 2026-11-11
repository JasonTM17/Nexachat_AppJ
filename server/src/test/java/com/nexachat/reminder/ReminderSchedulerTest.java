package com.nexachat.reminder;

import com.nexachat.conversation.ConversationMemberRepository;
import org.junit.jupiter.api.Test;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

class ReminderSchedulerTest {

    private static final Instant START = Instant.parse("2026-01-02T03:04:05Z");

    @Test
    void reminderSchedulerDoesNotUseAFixedPeriodicSchedule() {
        assertThat(Arrays.stream(ReminderScheduler.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Scheduled.class))
                .map(Method::getName))
                .isEmpty();
    }

    @Test
    void emptyReminderTableIsNotQueriedBetweenThirtyMinuteSafetyTicks() {
        Harness harness = new Harness(Duration.ofMinutes(30), true);
        harness.scheduler().start();

        assertThat(harness.scheduledAt()).isEqualTo(START.plus(Duration.ofMinutes(30)));
        assertThat(harness.wakeQueries()).hasValue(1);

        harness.clock().advance(Duration.ofMinutes(29));
        assertThat(harness.wakeQueries()).hasValue(1);
        assertThat(harness.claimQueries()).hasValue(0);

        harness.clock().advance(Duration.ofMinutes(1));
        harness.runScheduledTask();
        assertThat(harness.wakeQueries()).hasValue(2);
        assertThat(harness.claimQueries()).hasValue(1);
        assertThat(harness.scheduledAt()).isEqualTo(START.plus(Duration.ofMinutes(60)));

        harness.clock().advance(Duration.ofMinutes(29));
        assertThat(harness.wakeQueries()).hasValue(2);
        assertThat(harness.claimQueries()).hasValue(1);

        harness.clock().advance(Duration.ofMinutes(1));
        harness.runScheduledTask();
        assertThat(harness.wakeQueries()).hasValue(3);
        assertThat(harness.claimQueries()).hasValue(2);

        // Inclusive [start, start + 60 minutes]: 3 wake lookups and 2 due scans.
        assertThat(harness.wakeQueries().get() + harness.claimQueries().get()).isEqualTo(5);
        harness.scheduler().stop();
    }

    @Test
    void reminderDueInTenMinutesIsScheduledForItsDueTime() {
        Harness harness = new Harness(Duration.ofMinutes(30), true);
        harness.nextWakeDelayMillis().set(Duration.ofMinutes(10).toMillis());

        harness.scheduler().start();

        assertThat(harness.scheduledAt()).isEqualTo(START.plus(Duration.ofMinutes(10)));
        harness.clock().advance(Duration.ofMinutes(10));
        harness.runScheduledTask();
        assertThat(harness.claimQueries()).hasValue(1);
        harness.scheduler().stop();
    }

    @Test
    void databaseClockDelayDoesNotBecomeAnEarlyLoopWhenTheAppClockIsAhead() {
        Instant appNow = START.plus(Duration.ofMinutes(1));
        Harness harness = new Harness(Duration.ofMinutes(30), true, appNow);
        // PostgreSQL still reports 30 seconds until its due instant.
        harness.nextWakeDelayMillis().set(Duration.ofSeconds(30).toMillis());

        harness.scheduler().start();

        assertThat(harness.scheduledAt()).isEqualTo(appNow.plus(Duration.ofSeconds(30)));
        harness.clock().advance(Duration.ofSeconds(30));
        harness.runScheduledTask();
        assertThat(harness.claimQueries()).hasValue(1);
        harness.scheduler().stop();
    }

    @Test
    void committedScheduleChangeRearmsAnEarlierTimerAndCancelsTheOldOne() {
        Harness harness = new Harness(Duration.ofHours(1), true);
        harness.nextWakeDelayMillis().set(Duration.ofMinutes(45).toMillis());
        harness.scheduler().start();
        ScheduledFuture<?> oldTimer = harness.futures().get(0);

        harness.nextWakeDelayMillis().set(Duration.ofMinutes(10).toMillis());
        harness.scheduler().onScheduleChanged(new ReminderScheduleChangedEvent(UUID.randomUUID()));

        assertThat(harness.scheduledAt()).isEqualTo(START.plus(Duration.ofMinutes(10)));
        verify(oldTimer).cancel(false);
        assertThat(harness.futures()).hasSize(2);
        harness.scheduler().stop();
    }

    @Test
    void eventListenerRunsAfterCommitOnly() throws Exception {
        Method listener = ReminderScheduler.class.getMethod(
                "onScheduleChanged", ReminderScheduleChangedEvent.class);
        TransactionalEventListener annotation = listener.getAnnotation(TransactionalEventListener.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(annotation.fallbackExecution()).isFalse();
    }

    @Test
    void aRunAlreadyInProgressIsNotStartedAgain() throws Exception {
        Harness harness = new Harness(Duration.ofMinutes(30), true);
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
        Thread worker = new Thread(capturedTask, "reminder-scheduler-test");
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
        Harness harness = new Harness(Duration.ofMinutes(30), false);

        harness.scheduler().start();
        harness.scheduler().onScheduleChanged(new ReminderScheduleChangedEvent(UUID.randomUUID()));

        assertThat(harness.wakeQueries()).hasValue(0);
        assertThat(harness.futures()).isEmpty();
        verify(harness.taskScheduler(), never()).schedule(any(Runnable.class), any(Instant.class));
    }

    @Test
    void shutdownCancelsItsTimerAndMakesAnAlreadyDequeuedTaskInert() {
        Harness harness = new Harness(Duration.ofMinutes(30), true);
        harness.scheduler().start();
        Runnable capturedTask = harness.scheduledTask();
        ScheduledFuture<?> pending = harness.futures().get(0);

        harness.scheduler().stop();
        capturedTask.run();

        verify(pending).cancel(false);
        assertThat(harness.claimQueries()).hasValue(0);
        assertThat(harness.wakeQueries()).hasValue(1);
    }

    private static final class Harness {
        private final ReminderClaimService claimService = mock(ReminderClaimService.class);
        private final ConversationMemberRepository memberRepository = mock(ConversationMemberRepository.class);
        private final ReminderPushSender pushSender = mock(ReminderPushSender.class);
        private final ConversationReminderRepository reminderRepository =
                mock(ConversationReminderRepository.class);
        private final TaskScheduler taskScheduler = mock(TaskScheduler.class);
        private final MutableClock clock;
        private final AtomicInteger wakeQueries = new AtomicInteger();
        private final AtomicInteger claimQueries = new AtomicInteger();
        private final AtomicReference<Long> nextWakeDelayMillis = new AtomicReference<>();
        private final AtomicReference<Runnable> scheduledTask = new AtomicReference<>();
        private final AtomicReference<Instant> scheduledAt = new AtomicReference<>();
        private final java.util.ArrayList<ScheduledFuture<?>> futures = new java.util.ArrayList<>();
        private final ReminderScheduler scheduler;

        private Harness(Duration maxIdle, boolean enabled) {
            this(maxIdle, enabled, START);
        }

        private Harness(Duration maxIdle, boolean enabled, Instant appNow) {
            clock = new MutableClock(appNow);
            when(reminderRepository.findNextWakeDelayMillis(anyLong())).thenAnswer(invocation -> {
                wakeQueries.incrementAndGet();
                return nextWakeDelayMillis.get();
            });
            when(claimService.claimDue(anyLong(), any(UUID.class), anyInt())).thenAnswer(invocation -> {
                claimQueries.incrementAndGet();
                return List.of();
            });
            when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
                scheduledTask.set(invocation.getArgument(0));
                scheduledAt.set(invocation.getArgument(1));
                ScheduledFuture<?> future = mock(ScheduledFuture.class);
                futures.add(future);
                return future;
            });
            scheduler = new ReminderScheduler(claimService, memberRepository, pushSender,
                    reminderRepository, taskScheduler, 10, 600, enabled,
                    maxIdle.toMillis(), clock);
        }

        private ReminderScheduler scheduler() {
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

        private ReminderClaimService claimService() {
            return claimService;
        }

        private void runScheduledTask() {
            scheduledTask.get().run();
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
