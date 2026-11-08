package com.nexachat.message;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Scheduled retention for the delta-sync audit tables.
 *
 * <p>Reaction events are the only record of a removal, so they are kept for
 * {@code nexachat.sync.reaction-retention-days} (default 30). A delta request
 * whose {@code since} predates that window is answered with
 * {@code reactionsFullRefresh=true} instead of a partial page.
 *
 * <p>Idempotency rows are a replay cache, not an audit log: they expire after
 * {@code nexachat.idempotency.ttl-hours} (default 24), after which a replayed
 * key simply runs the operation again.
 */
@Component
@Profile("!test")
public class RetentionCleanupTask {

    private final ReactionEventRepository reactionEventRepository;
    private final RequestIdempotencyRepository requestIdempotencyRepository;
    private final SyncProperties syncProperties;

    private final long idempotencyTtlHours;

    public RetentionCleanupTask(ReactionEventRepository reactionEventRepository,
                                RequestIdempotencyRepository requestIdempotencyRepository,
                                SyncProperties syncProperties,
                                @Value("${nexachat.idempotency.ttl-hours:24}") long idempotencyTtlHours) {
        this.reactionEventRepository = reactionEventRepository;
        this.requestIdempotencyRepository = requestIdempotencyRepository;
        this.syncProperties = syncProperties;
        this.idempotencyTtlHours = idempotencyTtlHours;
    }

    @Scheduled(cron = "0 30 * * * *", zone = "UTC")
    public void purgeExpiredRows() {
        Instant now = Instant.now();
        reactionEventRepository.deleteOlderThan(
                now.minus(syncProperties.getReactionRetentionDays(), ChronoUnit.DAYS));
        requestIdempotencyRepository.deleteOlderThan(
                now.minus(idempotencyTtlHours, ChronoUnit.HOURS));
    }
}
