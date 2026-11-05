package com.nexachat.call;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class CallTimeoutSchedulerTest {

    private static final Instant START = Instant.parse("2026-11-05T08:00:00Z");

    @Test
    void timerFiringBeforeTheRingDeadlineIsRearmedInsteadOfLeavingTheCallRinging() {
        MutableClock clock = new MutableClock(START);
        CallRegistry registry = new CallRegistry(clock, 45_000);
        CallService service = mock(CallService.class);
        RecordingScheduler executor = new RecordingScheduler();
        CallTimeoutScheduler scheduler = new CallTimeoutScheduler(service, registry, 45_000, executor);
        UUID callId = UUID.randomUUID();
        registry.invite(callId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), START);

        scheduler.onCallRinging(new CallRingingEvent(callId));
        assertThat(executor.tasks).hasSize(1);
        assertThat(executor.delays.get(0)).isEqualTo(45_000L);

        // The timer fires slightly before the registry clock reaches the deadline.
        clock.set(START.plusMillis(44_999));
        executor.tasks.remove(0).run();

        verify(service, times(1)).timeout(callId);
        assertThat(executor.tasks).as("re-armed while still ringing").hasSize(1);
    }

    @Test
    void settledCallsAreNotRearmed() {
        MutableClock clock = new MutableClock(START);
        CallRegistry registry = new CallRegistry(clock, 45_000);
        CallService service = mock(CallService.class);
        RecordingScheduler executor = new RecordingScheduler();
        CallTimeoutScheduler scheduler = new CallTimeoutScheduler(service, registry, 45_000, executor);
        UUID conversation = UUID.randomUUID();
        UUID caller = UUID.randomUUID();
        UUID callId = UUID.randomUUID();
        registry.invite(callId, conversation, caller, UUID.randomUUID(), START);
        scheduler.onCallRinging(new CallRingingEvent(callId));

        registry.cancel(callId, conversation, caller, START.plusSeconds(1));
        executor.tasks.remove(0).run();

        assertThat(executor.tasks).isEmpty();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant value) {
            this.now = value;
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

    private static final class RecordingScheduler extends AbstractExecutorService
            implements ScheduledExecutorService {
        final List<Runnable> tasks = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            tasks.add(command);
            delays.add(unit.toMillis(delay));
            return new NoopFuture();
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay,
                                                      long period, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay,
                                                         long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException();
        }
    }

    private static final class NoopFuture implements ScheduledFuture<Object> {
        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return true;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
