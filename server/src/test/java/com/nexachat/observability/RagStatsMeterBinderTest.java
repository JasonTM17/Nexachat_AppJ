package com.nexachat.observability;

import com.nexachat.rag.RagStats;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RagStatsMeterBinderTest {

    @Test
    void exposesRagStatsSnapshotAsMonotonicCounters() {
        RagStats stats = new RagStats();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new RagStatsMeterBinder(stats, registry);
        stats.recordRequest();
        stats.recordCacheHit();
        stats.recordLlmCall(100);
        stats.recordLlmFailure();
        stats.recordEmbeddingCall(2);
        stats.recordEmbeddingFailure();
        stats.recordIndexedMessages(5);

        assertThat(registry.get("nexachat.rag.stats.requests").functionCounter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("nexachat.rag.stats.cache_hits").functionCounter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("nexachat.rag.stats.llm_calls").functionCounter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("nexachat.rag.stats.llm_failures").functionCounter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("nexachat.rag.stats.embedding_calls").functionCounter().count())
                .isEqualTo(2.0);
        assertThat(registry.get("nexachat.rag.stats.embedding_failures").functionCounter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("nexachat.rag.stats.indexed_messages").functionCounter().count())
                .isEqualTo(5.0);
    }
}
