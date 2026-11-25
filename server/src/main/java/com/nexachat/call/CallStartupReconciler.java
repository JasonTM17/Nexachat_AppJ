package com.nexachat.call;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Closes call rows orphaned by a restart. The call registry is in-memory, so
 * after a reboot no RINGING/ACTIVE row can still have a live server session —
 * the clients' best-effort END frames land on CALL_NOT_FOUND and without this
 * sweep the rows would ring in history forever.
 */
@Component
public class CallStartupReconciler {

    private static final Logger log = LoggerFactory.getLogger(CallStartupReconciler.class);

    private final CallRecordRepository repository;

    public CallStartupReconciler(CallRecordRepository repository) {
        this.repository = repository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void reconcileAtStartup() {
        int closed = closeOrphanedCalls();
        if (closed > 0) {
            log.info("Closed {} orphaned call record(s) left non-terminal by a restart", closed);
        }
    }

    int closeOrphanedCalls() {
        List<CallRecord> orphaned = repository.findByStateIn(List.of(CallState.RINGING, CallState.ACTIVE));
        if (orphaned.isEmpty()) {
            return 0;
        }
        Instant sweepEndedAt = Instant.now();
        orphaned.forEach(record -> record.closeOrphaned(sweepEndedAt));
        repository.saveAll(orphaned);
        return orphaned.size();
    }
}
