package com.nexachat.reminder;

import com.nexachat.conversation.ConversationMemberRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;

/**
 * Event-driven scheduler for due conversation reminders.
 *
 * <p>State lives entirely in {@code conversation_reminders}; the in-memory
 * timer is disposable. At startup and after each run it schedules the earlier
 * of the next due/lease-expiry moment and the configured maximum idle interval.
 * Reminder CRUD re-arms it after commit, while the maximum idle interval
 * catches changes made by other instances.</p>
 *
 * <p>The database claim protocol remains the authority when two instances wake:
 * rows are locked with {@code FOR UPDATE SKIP LOCKED}, claimed with a lease
 * token, pushed to current members, then advanced under the row lock. The lease
 * makes a crash recoverable and preserves at-least-once delivery.</p>
 */
@Component
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);
    private static final long MIN_WAKE_DELAY_MILLIS = 50L;

    private final ReminderClaimService claimService;
    private final ConversationMemberRepository memberRepository;
    private final ReminderPushSender pushSender;
    private final ConversationReminderRepository reminderRepository;
    private final TaskScheduler taskScheduler;
    private final int batchSize;
    private final long claimLeaseSeconds;
    private final boolean enabled;
    private final long maxIdleMillis;
    private final Clock clock;

    private final Object timerLock = new Object();
    private ScheduledFuture<?> pendingRun;
    private long timerGeneration;
    private boolean runInProgress;
    private boolean shutdown;

    @Autowired
    public ReminderScheduler(ReminderClaimService claimService,
                             ConversationMemberRepository memberRepository,
                             ReminderPushSender pushSender,
                             ConversationReminderRepository reminderRepository,
                             @Value("${nexachat.reminders.batch-size:10}") int batchSize,
                             @Value("${nexachat.reminders.claim-lease-seconds:600}") long claimLeaseSeconds,
                             @Value("${nexachat.reminders.enabled:true}") boolean enabled,
                             @Value("${nexachat.reminders.max-idle-ms:1000}") long maxIdleMillis) {
        this(claimService, memberRepository, pushSender, reminderRepository, newTaskScheduler(),
                batchSize, claimLeaseSeconds, enabled, maxIdleMillis, Clock.systemUTC());
    }

    ReminderScheduler(ReminderClaimService claimService,
                      ConversationMemberRepository memberRepository,
                      ReminderPushSender pushSender,
                      ConversationReminderRepository reminderRepository,
                      TaskScheduler taskScheduler,
                      int batchSize,
                      long claimLeaseSeconds,
                      boolean enabled,
                      long maxIdleMillis,
                      Clock clock) {
        this.claimService = claimService;
        this.memberRepository = memberRepository;
        this.pushSender = pushSender;
        this.reminderRepository = reminderRepository;
        this.taskScheduler = taskScheduler;
        this.batchSize = Math.max(1, batchSize);
        this.claimLeaseSeconds = Math.max(1L, claimLeaseSeconds);
        this.enabled = enabled;
        this.maxIdleMillis = Math.max(MIN_WAKE_DELAY_MILLIS, maxIdleMillis);
        this.clock = clock;
    }

    @PostConstruct
    public void start() {
        if (enabled) {
            scheduleNext();
        }
    }

    /**
     * One claim/delivery cycle. Public so integration tests can drive a
     * deterministic restart or race without waiting for the timer.
     *
     * @return number of reminders whose state advanced
     */
    public int runOnce() {
        UUID claimToken = UUID.randomUUID();

        List<UUID> claimed = claimService.claimDue(claimLeaseSeconds, claimToken, batchSize);
        if (claimed.isEmpty()) {
            return 0;
        }

        int advanced = 0;
        for (UUID reminderId : claimed) {
            try {
                Optional<ReminderDelivery> snapshot = claimService.findDelivery(reminderId, claimToken);
                if (snapshot.isEmpty()) {
                    continue;
                }
                ReminderDelivery delivery = snapshot.get();
                List<UUID> memberIds = memberRepository.findMemberUserIds(delivery.conversationId());
                pushSender.send(delivery, memberIds);
                if (claimService.advance(reminderId, claimToken)) {
                    advanced++;
                }
            } catch (RuntimeException failure) {
                // The claim is left in place; it becomes due again when the
                // lease expires, so a transient database or push failure does
                // not lose the reminder.
                log.warn("Reminder {} dispatch failed ({}); it will be retried after the claim lease",
                        reminderId, failure.getClass().getSimpleName());
            }
        }
        if (advanced > 0) {
            log.info("Advanced {} due reminder(s)", advanced);
        }
        return advanced;
    }

    /** Recompute from committed state after create, update or delete. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onScheduleChanged(ReminderScheduleChangedEvent event) {
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

        long delayMillis = maxIdleMillis;
        try {
            Long nextWakeDelayMillis = reminderRepository.findNextWakeDelayMillis(claimLeaseSeconds);
            if (nextWakeDelayMillis != null) {
                delayMillis = Math.min(maxIdleMillis, Math.max(0L, nextWakeDelayMillis));
            }
        } catch (RuntimeException failure) {
            // A transient lookup failure must not disable reminder delivery.
            // Retry at the safety interval and let runOnce report its own error.
            log.warn("Reminder wake-up lookup failed ({}); retrying after the max-idle interval",
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
            log.warn("Reminder wake-up timer was not accepted by the task scheduler");
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
            log.warn("Reminder scheduled run failed ({})", failure.getClass().getSimpleName());
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
        scheduler.setThreadNamePrefix("reminder-wakeup-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.initialize();
        return scheduler;
    }
}
