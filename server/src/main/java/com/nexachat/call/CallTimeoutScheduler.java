package com.nexachat.call;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
public class CallTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(CallTimeoutScheduler.class);
    private static final long RETRY_DELAY_MILLIS = 25L;
    private static final long PERSISTENCE_RETRY_DELAY_MILLIS = 1_000L;
    static final int MAX_PERSISTENCE_RETRIES = 5;

    private final CallService callService;
    private final CallRegistry registry;
    private final long ringTimeoutMillis;
    private final long disconnectGraceMs;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<UUID, ScheduledFuture<?>> scheduledCalls = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ScheduledFuture<?>> scheduledDisconnects = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> persistenceRetries = new ConcurrentHashMap<>();

    @Autowired
    public CallTimeoutScheduler(CallService callService, CallRegistry registry,
                                @Value("${nexachat.calls.ring-timeout-ms:45000}") long ringTimeoutMillis,
                                @Value("${nexachat.calls.disconnect-grace-ms:15000}") long disconnectGraceMs) {
        this(callService, registry, ringTimeoutMillis, disconnectGraceMs,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "nexachat-call-timeouts");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    public CallTimeoutScheduler(CallService callService, CallRegistry registry,
                                long ringTimeoutMillis) {
        this(callService, registry, ringTimeoutMillis, 15_000L);
    }

    CallTimeoutScheduler(CallService callService, CallRegistry registry, long ringTimeoutMillis,
                         long disconnectGraceMs, ScheduledExecutorService scheduler) {
        this.callService = callService;
        this.registry = registry;
        this.ringTimeoutMillis = ringTimeoutMillis;
        this.disconnectGraceMs = disconnectGraceMs;
        this.scheduler = scheduler;
    }

    CallTimeoutScheduler(CallService callService, CallRegistry registry, long ringTimeoutMillis,
                         ScheduledExecutorService scheduler) {
        this(callService, registry, ringTimeoutMillis, 15_000L, scheduler);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCallRinging(CallRingingEvent event) {
        scheduleTimeout(event.callId(), 1L);
    }

    private void scheduleTimeout(UUID callId, long minimumDelayMillis) {
        registry.find(callId).ifPresent(session -> {
            long elapsed = Math.max(0L, Duration.between(session.startedAt(), registry.now()).toMillis());
            long delay = Math.max(minimumDelayMillis, ringTimeoutMillis - elapsed);
            ScheduledFuture<?> next = scheduler.schedule(() -> fire(callId), delay, TimeUnit.MILLISECONDS);
            ScheduledFuture<?> previous = scheduledCalls.put(callId, next);
            if (previous != null) {
                previous.cancel(false);
            }
        });
    }

    private void fire(UUID callId) {
        scheduledCalls.remove(callId);
        try {
            callService.timeout(callId);
        } catch (RuntimeException failure) {
            boolean stillRinging = registry.find(callId)
                    .filter(session -> session.state() == CallState.RINGING)
                    .isPresent();
            if (stillRinging) {
                schedulePersistenceRetry(callId);
            } else {
                persistenceRetries.remove(callId);
            }
            return;
        }
        persistenceRetries.remove(callId);
        // The timer and the registry clock are independent: a timer that fires a
        // moment before the ring deadline leaves the call ringing, so it is
        // re-armed instead of being left to ring forever.
        registry.find(callId)
                .filter(session -> session.state() == CallState.RINGING)
                .ifPresent(session -> scheduleTimeout(callId, RETRY_DELAY_MILLIS));
    }

    private void schedulePersistenceRetry(UUID callId) {
        int attempt = persistenceRetries.merge(callId, 1, Integer::sum);
        if (attempt > MAX_PERSISTENCE_RETRIES) {
            persistenceRetries.remove(callId);
            log.warn("Stopped retrying call timeout persistence for {} after {} failed attempts",
                    callId, attempt);
            return;
        }
        scheduleTimeout(callId, backoffMillis(attempt));
    }

    static long backoffMillis(int failedAttempt) {
        int exponent = Math.min(Math.max(failedAttempt, 1), MAX_PERSISTENCE_RETRIES) - 1;
        return PERSISTENCE_RETRY_DELAY_MILLIS << exponent;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCallTerminal(CallTerminalEvent event) {
        persistenceRetries.remove(event.callId());
        ScheduledFuture<?> future = scheduledCalls.remove(event.callId());
        if (future != null) {
            future.cancel(false);
        }
        registry.find(event.callId()).ifPresent(session -> {
            cancelScheduledDisconnect(session.callerId());
            cancelScheduledDisconnect(session.calleeId());
        });
    }

    /**
     * Defers the disconnect check until presence has processed the event.
     * When the user is currently in an ACTIVE call, applies a configurable disconnect grace window
     * (default 15s) to protect against transient network roaming disconnects (Wi-Fi <-> LTE/5G).
     */
    public void checkDisconnectedUser(UUID userId, Runnable cleanup) {
        long delayMillis = 50L;
        java.util.Optional<CallSession> active = registry.activeCallForUser(userId);
        if (active.isPresent() && active.get().state() == CallState.ACTIVE) {
            delayMillis = disconnectGraceMs;
            log.info("Scheduling {}ms disconnect grace window for user {} in active call {}",
                    delayMillis, userId, active.get().callId());
        }
        ScheduledFuture<?> previous = scheduledDisconnects.remove(userId);
        if (previous != null) {
            previous.cancel(false);
            log.info("Cancelled prior pending disconnect future for user {}", userId);
        }
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            scheduledDisconnects.remove(userId);
            cleanup.run();
        }, delayMillis, TimeUnit.MILLISECONDS);
        scheduledDisconnects.put(userId, future);
    }

    public void cancelScheduledDisconnect(UUID userId) {
        ScheduledFuture<?> future = scheduledDisconnects.remove(userId);
        if (future != null) {
            future.cancel(false);
            log.info("Cancelled pending disconnect grace window for user {}", userId);
        }
    }

    public void cancelDisconnectedUser(UUID userId) {
        cancelScheduledDisconnect(userId);
    }

    public void retryDisconnectedUser(Runnable cleanup, int failedAttempt) {
        scheduler.schedule(cleanup, backoffMillis(failedAttempt), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        for (ScheduledFuture<?> future : scheduledCalls.values()) {
            future.cancel(false);
        }
        scheduledCalls.clear();
        for (ScheduledFuture<?> future : scheduledDisconnects.values()) {
            future.cancel(false);
        }
        scheduledDisconnects.clear();
        scheduler.shutdownNow();
    }
}
