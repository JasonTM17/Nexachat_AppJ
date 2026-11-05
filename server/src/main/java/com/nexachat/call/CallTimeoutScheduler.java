package com.nexachat.call;

import jakarta.annotation.PreDestroy;
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

    private final CallService callService;
    private final CallRegistry registry;
    private final long ringTimeoutMillis;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<UUID, ScheduledFuture<?>> scheduledCalls = new ConcurrentHashMap<>();

    @Autowired
    public CallTimeoutScheduler(CallService callService, CallRegistry registry,
                                @Value("${nexachat.calls.ring-timeout-ms:45000}") long ringTimeoutMillis) {
        this(callService, registry, ringTimeoutMillis,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "nexachat-call-timeouts");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    CallTimeoutScheduler(CallService callService, CallRegistry registry, long ringTimeoutMillis,
                         ScheduledExecutorService scheduler) {
        this.callService = callService;
        this.registry = registry;
        this.ringTimeoutMillis = ringTimeoutMillis;
        this.scheduler = scheduler;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCallRinging(CallRingingEvent event) {
        registry.find(event.callId()).ifPresent(session -> {
            long elapsed = Math.max(0L, Duration.between(session.startedAt(), registry.now()).toMillis());
            long delay = Math.max(0L, ringTimeoutMillis - elapsed);
            ScheduledFuture<?> next = scheduler.schedule(
                    () -> {
                        scheduledCalls.remove(event.callId());
                        callService.timeout(event.callId());
                    }, Math.max(1L, delay), TimeUnit.MILLISECONDS);
            ScheduledFuture<?> previous = scheduledCalls.put(event.callId(), next);
            if (previous != null) {
                previous.cancel(false);
            }
        });
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCallTerminal(CallTerminalEvent event) {
        ScheduledFuture<?> future = scheduledCalls.remove(event.callId());
        if (future != null) {
            future.cancel(false);
        }
    }

    /** Defers the check until PresenceSessionRegistry has processed the same disconnect event. */
    public void checkDisconnectedUser(UUID userId, Runnable cleanup) {
        scheduler.schedule(cleanup, 50, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
