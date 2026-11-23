package com.nexachat.scheduled;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Event-driven scheduler for due scheduled messages.
 *
 * <p>State lives entirely in {@code scheduled_messages}; the in-memory timer
 * is disposable. At startup and after each run it schedules the earlier of
 * the next due/lease-expiry/retry moment and the configured poll interval —
 * the poll is the safety net for changes made by other instances or while
 * this one slept (Render free tier), and the boot wake-up is the catch-up.
 * CRUD and dispatch re-arm the timer after commit via
 * {@link ScheduledMessageChangedEvent}.</p>
 *
 * <p>The database claim protocol remains the authority when two instances
 * wake: rows are locked with {@code FOR UPDATE SKIP LOCKED}, claimed with a
 * lease token, then sent and marked in separate per-row transactions on a
 * small worker pool — the claim transaction never overlaps a send. One tick
 * keeps draining due batches until the table is empty or the drain budget is
 * spent, then yields; a slower send simply resolves next tick under its still
 * valid claim lease.</p>
 */
@Component
public class ScheduledMessageScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScheduledMessageScheduler.class);
    private static final long MIN_WAKE_DELAY_MILLIS = 50L;

    private final ScheduledMessageClaimService claimService;
    private final ScheduledMessageDispatcher dispatcher;
    private final ScheduledMessageRepository scheduledMessageRepository;
    private final TaskScheduler taskScheduler;
    private final AsyncTaskExecutor dispatchExecutor;
    private final int batchSize;
    private final long claimLeaseSeconds;
    private final boolean enabled;
    private final long pollMillis;
    private final long drainBudgetMillis;
    private final Clock clock;

    private final Object timerLock = new Object();
    private ScheduledFuture<?> pendingRun;
    private long timerGeneration;
    private boolean runInProgress;
    private boolean shutdown;

    @Autowired
    public ScheduledMessageScheduler(ScheduledMessageClaimService claimService,
                                     ScheduledMessageDispatcher dispatcher,
                                     ScheduledMessageRepository scheduledMessageRepository,
                                     @Value("${nexachat.scheduled.batch-size:20}") int batchSize,
                                     @Value("${nexachat.scheduled.claim-lease-seconds:60}")
                                     long claimLeaseSeconds,
                                     @Value("${nexachat.scheduled.enabled:true}") boolean enabled,
                                     @Value("${nexachat.scheduled.poll-ms:15000}") long pollMillis,
                                     @Value("${nexachat.scheduled.drain-budget-ms:30000}")
                                     long drainBudgetMillis,
                                     @Value("${nexachat.scheduled.worker-threads:4}") int workerThreads) {
        this(claimService, dispatcher, scheduledMessageRepository, newTaskScheduler(),
                newDispatchExecutor(workerThreads), batchSize, claimLeaseSeconds, enabled,
                pollMillis, drainBudgetMillis, Clock.systemUTC());
    }

    ScheduledMessageScheduler(ScheduledMessageClaimService claimService,
                              ScheduledMessageDispatcher dispatcher,
                              ScheduledMessageRepository scheduledMessageRepository,
                              TaskScheduler taskScheduler,
                              AsyncTaskExecutor dispatchExecutor,
                              int batchSize,
                              long claimLeaseSeconds,
                              boolean enabled,
                              long pollMillis,
                              long drainBudgetMillis,
                              Clock clock) {
        this.claimService = claimService;
        this.dispatcher = dispatcher;
        this.scheduledMessageRepository = scheduledMessageRepository;
        this.taskScheduler = taskScheduler;
        this.dispatchExecutor = dispatchExecutor;
        this.batchSize = Math.max(1, batchSize);
        this.claimLeaseSeconds = Math.max(1L, claimLeaseSeconds);
        this.enabled = enabled;
        this.pollMillis = Math.max(MIN_WAKE_DELAY_MILLIS, pollMillis);
        this.drainBudgetMillis = Math.max(1L, drainBudgetMillis);
        this.clock = clock;
    }

    @PostConstruct
    public void start() {
        if (enabled) {
            scheduleNext();
        }
    }

    /**
     * One claim/dispatch cycle. Public so integration tests can drive a
     * deterministic restart or race without waiting for the timer. Drains
     * due batches until none remain or the drain budget is spent.
     *
     * @return number of rows this run resolved (sent, failed or parked)
     */
    public int runOnce() {
        UUID claimToken = UUID.randomUUID();
        long deadline = clock.millis() + drainBudgetMillis;
        int resolved = 0;
        while (true) {
            List<UUID> claimed = claimService.claimDue(claimLeaseSeconds, claimToken, batchSize);
            if (claimed.isEmpty()) {
                break;
            }
            resolved += dispatchBatch(claimed, claimToken, deadline);
            if (clock.millis() >= deadline) {
                break;
            }
        }
        if (resolved > 0) {
            log.info("Resolved {} due scheduled message(s)", resolved);
        }
        return resolved;
    }

    /**
     * Fans a claimed batch out over the worker pool and waits for each row
     * within the remaining drain budget. A row still running at the deadline
     * is left alone — its claim lease keeps ownership until the lease expires
     * or the row commits.
     */
    private int dispatchBatch(List<UUID> claimed, UUID claimToken, long deadline) {
        List<Future<DispatchOutcome>> futures = new ArrayList<>(claimed.size());
        for (UUID id : claimed) {
            futures.add(dispatchExecutor.submit(() -> processClaimed(id, claimToken)));
        }
        Map<DispatchOutcome, Integer> outcomes = new EnumMap<>(DispatchOutcome.class);
        for (Future<DispatchOutcome> future : futures) {
            try {
                long remaining = deadline - clock.millis();
                DispatchOutcome outcome = future.get(Math.max(1L, remaining), TimeUnit.MILLISECONDS);
                outcomes.merge(outcome, 1, Integer::sum);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (java.util.concurrent.TimeoutException timeout) {
                // The row's transaction may still commit; the claim lease
                // decides whether this or another run owns the final state.
                log.debug("Scheduled message dispatch did not complete within the drain budget");
            } catch (java.util.concurrent.ExecutionException failure) {
                // The claim stays in place; the row becomes dispatchable
                // again when the lease expires, so a transient database
                // failure in the snapshot read does not lose the message.
                log.warn("Scheduled message dispatch failed ({}); it will be retried after the claim lease",
                        failure.getCause() == null ? failure.getClass().getSimpleName()
                                : failure.getCause().getClass().getSimpleName());
            }
        }
        return outcomes.values().stream().mapToInt(Integer::intValue).sum();
    }

    private DispatchOutcome processClaimed(UUID id, UUID claimToken) {
        Optional<ScheduledMessageDispatch> dispatch = claimService.findDispatch(id, claimToken);
        return dispatch.map(dispatcher::process).orElse(DispatchOutcome.CLAIM_LOST);
    }

    /** Recompute from committed state after create, cancel or terminal mark. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onScheduleChanged(ScheduledMessageChangedEvent event) {
        if (enabled) {
            scheduleNext();
        }
    }

    @PreDestroy
    public void stop() {
        synchronized (timerLock) {
            shutdown = true;
            timerGeneration++;
            if (pendingRun != null) {
                pendingRun.cancel(false);
                pendingRun = null;
            }
        }
        if (taskScheduler instanceof ThreadPoolTaskScheduler ownedScheduler) {
            ownedScheduler.shutdown();
        }
        if (dispatchExecutor instanceof ThreadPoolTaskExecutor ownedExecutor) {
            ownedExecutor.shutdown();
        }
    }

    private void scheduleNext() {
        synchronized (timerLock) {
            scheduleNextLocked();
        }
    }

    private void scheduleNextLocked() {
        if (shutdown || !enabled) {
            return;
        }

        long delayMillis = pollMillis;
        try {
            Long nextWakeDelayMillis =
                    scheduledMessageRepository.findNextWakeDelayMillis(claimLeaseSeconds);
            if (nextWakeDelayMillis != null) {
                delayMillis = Math.min(pollMillis, Math.max(0L, nextWakeDelayMillis));
            }
        } catch (RuntimeException failure) {
            // A transient lookup failure must not disable dispatching.
            // Retry at the poll interval and let runOnce report its own error.
            log.warn("Scheduled-message wake-up lookup failed ({}); retrying after the poll interval",
                    failure.getClass().getSimpleName());
        }

        Instant now = clock.instant();
        Instant target = now.plusMillis(delayMillis);
        Instant floor = now.plusMillis(MIN_WAKE_DELAY_MILLIS);
        if (target.isBefore(floor)) {
            target = floor;
        }

        if (pendingRun != null) {
            pendingRun.cancel(false);
        }
        long generation = ++timerGeneration;
        pendingRun = taskScheduler.schedule(() -> runScheduled(generation), target);
        if (pendingRun == null) {
            log.warn("Scheduled-message wake-up timer was not accepted by the task scheduler");
        }
    }

    private void runScheduled(long generation) {
        synchronized (timerLock) {
            if (shutdown || !enabled || generation != timerGeneration) {
                return;
            }
            pendingRun = null;
            if (runInProgress) {
                return;
            }
            runInProgress = true;
        }

        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.warn("Scheduled-message run failed ({})", failure.getClass().getSimpleName());
        } finally {
            synchronized (timerLock) {
                runInProgress = false;
                scheduleNextLocked();
            }
        }
    }

    private static ThreadPoolTaskScheduler newTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("scheduled-message-wakeup-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler;
    }

    private static ThreadPoolTaskExecutor newDispatchExecutor(int workerThreads) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, workerThreads));
        executor.setMaxPoolSize(Math.max(1, workerThreads));
        executor.setThreadNamePrefix("scheduled-message-dispatch-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
