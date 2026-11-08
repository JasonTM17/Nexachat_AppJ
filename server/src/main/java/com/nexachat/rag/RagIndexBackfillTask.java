package com.nexachat.rag;

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
    private final AtomicBoolean running = new AtomicBoolean();

    public RagIndexBackfillTask(RagEmbeddingService embeddings, RagProperties properties) {
        this.embeddings = embeddings;
        this.properties = properties;
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
            embeddings.backfill();
        } catch (RuntimeException exception) {
            // Degrade: the lazy path still indexes at query time.
        } finally {
            running.set(false);
        }
    }
}
