package com.nexachat.disappearing;

import com.nexachat.ops.ScheduledAdvisoryLock;
import com.nexachat.ops.ScheduledLockIds;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The scheduled disappearing-message sweep. A session-level advisory lock
 * keeps the sweep single-flight across application instances; the cadence is
 * deliberately coarse (default 60s, Render-coarse in that profile) because
 * expiry precision is bounded by the timer granularity anyway. The bean is
 * excluded from the database-less {@code test} profile like the other
 * schedulers.
 */
@Component
@Profile("!test")
public class DisappearingMessagePurgeJob {

    private final ScheduledAdvisoryLock advisoryLock;
    private final DisappearingMessagePurger purger;

    public DisappearingMessagePurgeJob(ScheduledAdvisoryLock advisoryLock,
                                       DisappearingMessagePurger purger) {
        this.advisoryLock = advisoryLock;
        this.purger = purger;
    }

    @Scheduled(fixedDelayString = "${nexachat.disappearing.purge-interval-ms:60000}",
            initialDelayString = "${nexachat.disappearing.purge-interval-ms:60000}")
    public void scheduledPurge() {
        advisoryLock.withLock(ScheduledLockIds.DISAPPEARING_SWEEP, purger::purgeBatch);
    }
}
