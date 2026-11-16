package com.nexachat.disappearing;

import com.nexachat.message.MessageRepository;
import com.nexachat.message.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * One bounded disappearing-message purge batch: finds live rows whose
 * {@code expires_at} has passed and expires each through
 * {@link MessageService#expireMessage} — the shared soft-delete cascade — in
 * its own transaction so one failure cannot roll back the rest of the batch.
 * Rows the timer cleared (or that were already deleted) between the scan and
 * the lock are skipped by the re-check inside {@code expireMessage}, so the
 * sweep is idempotent and safe to re-run.
 */
@Service
public class DisappearingMessagePurger {

    private static final Logger log = LoggerFactory.getLogger(DisappearingMessagePurger.class);

    private final MessageRepository messageRepository;
    private final MessageService messageService;
    private final DisappearingProperties properties;
    private final Clock clock;

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
        List<UUID> expired = messageRepository.findExpiredIds(
                clock.instant(), PageRequest.of(0, properties.getBatchSize()));
        int purged = 0;
        for (UUID id : expired) {
            try {
                if (messageService.expireMessage(id)) {
                    purged++;
                }
            } catch (RuntimeException failure) {
                log.warn("Disappearing purge skipped message {} ({})",
                        id, failure.getClass().getSimpleName());
            }
        }
        if (!expired.isEmpty()) {
            log.info("Disappearing purge expired {} of {} due messages", purged, expired.size());
        }
        return purged;
    }
}
