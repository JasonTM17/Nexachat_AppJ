package com.nexachat.disappearing;

import com.nexachat.message.MessageRepository;
import com.nexachat.message.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One bounded disappearing-message purge batch: finds live rows whose
 * {@code expires_at} has passed and expires each through
 * {@link MessageService#expireMessage} — the shared soft-delete cascade — in
 * its own transaction so one failure cannot roll back the rest of the batch.
 * Rows the timer cleared (or that were already deleted) between the scan and
 * the lock are skipped by the re-check inside {@code expireMessage}, so the
 * sweep is idempotent and safe to re-run.
 *
 * <p>A row that fails every sweep (a poison row: corrupted content, a stuck
 * cascade) would otherwise sit at the head of the expiry order forever and,
 * once poison rows fill a whole page, crowd every later row out of the
 * bounded batch. After {@value #MAX_CONSECUTIVE_FAILURES} consecutive
 * failures a row is parked like the scheduled-message dead letter: for
 * {@link #POISON_PARK} the sweep stops spending attempts and page slots on
 * it and scans past it so the rows behind are still processed, then retries
 * it once when the park lapses and dead-letters it again if it still fails.
 */
@Service
public class DisappearingMessagePurger {

    private static final Logger log = LoggerFactory.getLogger(DisappearingMessagePurger.class);

    /** Consecutive sweeps one row may fail before the sweep parks it. */
    static final int MAX_CONSECUTIVE_FAILURES = 3;

    /** How long a repeatedly failing row is skipped before one retry attempt. */
    static final Duration POISON_PARK = Duration.ofHours(1);

    /**
     * Pages one sweep may read past parked poison rows, so a badly poisoned
     * backlog keeps the sweep bounded instead of scanning the whole table.
     */
    private static final int MAX_SCAN_PAGES = 5;

    private final MessageRepository messageRepository;
    private final MessageService messageService;
    private final DisappearingProperties properties;
    private final Clock clock;

    /** Per-row consecutive-failure bookkeeping; cleared on the first success. */
    private final Map<UUID, PoisonRow> poisonRows = new ConcurrentHashMap<>();

    /** {@code parkedUntil} is {@link Instant#EPOCH} while the row is not parked. */
    private record PoisonRow(int consecutiveFailures, Instant parkedUntil) {
    }

    public DisappearingMessagePurger(MessageRepository messageRepository,
                                     MessageService messageService,
                                     DisappearingProperties properties,
                                     Clock clock) {
        this.messageRepository = messageRepository;
        this.messageService = messageService;
        this.properties = properties;
        this.clock = clock;
    }

    /** Runs one batch; returns the number of messages tombstoned. */
    public int purgeBatch() {
        if (!properties.isEnabled()) {
            return 0;
        }
        Instant now = clock.instant();
        int purged = 0;
        int scanned = 0;
        int parkedSkipped = 0;
        int attempted = 0;
        // expireMessage tombstones rows while the sweep walks, so a later page
        // can repeat an earlier id; every row is attempted at most once per run.
        Set<UUID> attemptedIds = new HashSet<>();
        for (int page = 0; page < MAX_SCAN_PAGES; page++) {
            List<UUID> expired = messageRepository.findExpiredIds(
                    now, PageRequest.of(page, properties.getBatchSize()));
            scanned += expired.size();
            for (UUID id : expired) {
                if (!attemptedIds.add(id)) {
                    continue;
                }
                PoisonRow poison = poisonRows.get(id);
                if (poison != null && now.isBefore(poison.parkedUntil())) {
                    parkedSkipped++;
                    continue;
                }
                try {
                    if (messageService.expireMessage(id)) {
                        purged++;
                    }
                    poisonRows.remove(id);
                } catch (RuntimeException failure) {
                    parkAfterRepeatedFailure(id, now, failure);
                }
                attempted++;
            }
            // A short page is the end of the expired backlog; otherwise the
            // sweep keeps reading pages only while parked poison rows are
            // still consuming this batch's slots.
            if (attempted >= properties.getBatchSize()
                    || expired.size() < properties.getBatchSize()) {
                break;
            }
        }
        if (scanned > 0) {
            log.info("Disappearing purge expired {} of {} due messages ({} parked poison rows skipped)",
                    purged, scanned, parkedSkipped);
        }
        return purged;
    }

    /**
     * Counts the failure and, once the same row failed
     * {@value #MAX_CONSECUTIVE_FAILURES} consecutive sweeps in its own
     * per-row transaction, parks it for {@link #POISON_PARK}.
     */
    private void parkAfterRepeatedFailure(UUID id, Instant now, RuntimeException failure) {
        PoisonRow previous = poisonRows.get(id);
        int failures = (previous == null ? 0 : previous.consecutiveFailures()) + 1;
        if (failures < MAX_CONSECUTIVE_FAILURES) {
            poisonRows.put(id, new PoisonRow(failures, Instant.EPOCH));
            log.warn("Disappearing purge skipped message {} ({}), attempt {} of {}",
                    id, failure.getClass().getSimpleName(), failures, MAX_CONSECUTIVE_FAILURES);
            return;
        }
        poisonRows.put(id, new PoisonRow(failures, now.plus(POISON_PARK)));
        log.warn("Disappearing purge parked message {} after {} failed sweeps ({}); retrying in {}",
                id, failures, failure.getClass().getSimpleName(), POISON_PARK);
    }
}
