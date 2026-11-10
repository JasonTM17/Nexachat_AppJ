package com.nexachat.auth;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

/** Bounded, race-safe ciphertext scrubbing that is independent of general retention settings. */
@Component
public class RefreshReplayCleanup {

    private final RefreshTokenRepository repository;
    private final RefreshReplayProperties properties;
    private final Clock clock;

    public RefreshReplayCleanup(RefreshTokenRepository repository,
                                RefreshReplayProperties properties,
                                Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void scrubAtStartup() {
        scrubExpiredPayloads();
    }

    @Scheduled(fixedDelayString = "${nexachat.auth.refresh-replay.cleanup-interval-ms:60000}")
    @Transactional
    public void scrubPeriodically() {
        scrubExpiredPayloads();
    }

    @Transactional
    public int scrubExpiredPayloads() {
        int batchSize = Math.max(1, Math.min(properties.getCleanupBatchSize(), 5_000));
        return repository.scrubExpiredReplayPayloads(
                clock.instant().truncatedTo(ChronoUnit.MICROS), batchSize);
    }
}
