package com.nexachat.rag;

import com.nexachat.ops.ScheduledAdvisoryLock;
import com.nexachat.ops.ScheduledLockIds;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded background backfill. It never blocks message send and never runs
 * concurrently with itself; a provider failure leaves the remaining messages
 * for the next tick.
 */
@Component
@Profile("!test")
public class RagIndexBackfillTask {

    private final RagEmbeddingService embeddings;
    private final RagProperties properties;
    private final ScheduledAdvisoryLock advisoryLock;
    private final AtomicBoolean running = new AtomicBoolean();

    public RagIndexBackfillTask(RagEmbeddingService embeddings,
                                RagProperties properties,
                                ScheduledAdvisoryLock advisoryLock) {
        this.embeddings = embeddings;
        this.properties = properties;
        this.advisoryLock = advisoryLock;
    }

    @Scheduled(fixedDelayString = "${nexachat.rag.index.backfill-interval-ms:60000}")
    public void backfill() {
        if (!properties.isEnabled() || !properties.getIndex().isBackfillEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            // Acquire, run and release on the SAME pooled connection. Acquiring
            // and releasing through separate JdbcTemplate calls can land on two
            // different connections: the unlock then reports false and the
            // session-level lock stays held by a pooled connection that never
            // releases it, so later ticks silently stop backfilling.
            advisoryLock.withLock(ScheduledLockIds.RAG_INDEX_BACKFILL, () -> {
                embeddings.backfill();
                return Boolean.TRUE;
            });
        } catch (RuntimeException exception) {
            // Degrade: the lazy path still indexes at query time.
        } finally {
            running.set(false);
        }
    }
}
