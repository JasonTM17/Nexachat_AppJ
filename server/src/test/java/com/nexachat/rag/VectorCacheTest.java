package com.nexachat.rag;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VectorCacheTest {

    @Test
    void vectorsAreScopedByModelAndMessageId() {
        VectorCache cache = new VectorCache(8);
        UUID messageId = UUID.randomUUID();
        float[] external = {1.0f};
        float[] local = {2.0f};

        cache.put("openai-compatible:gemini-embedding-001", messageId, external);
        cache.put("lexical", messageId, local);

        assertThat(cache.get("openai-compatible:gemini-embedding-001", messageId)).isSameAs(external);
        assertThat(cache.get("lexical", messageId)).isSameAs(local);
        assertThat(cache.get("openai-compatible:other-model", messageId)).isNull();
    }

    @Test
    void putAllIsScopedByModel() {
        VectorCache cache = new VectorCache(8);
        UUID messageId = UUID.randomUUID();
        Map<UUID, float[]> vectors = new LinkedHashMap<>();
        vectors.put(messageId, new float[] {3.0f});

        cache.putAll("lexical", vectors);

        assertThat(cache.get("lexical", messageId)).isNotNull();
        assertThat(cache.get("openai-compatible:gemini-embedding-001", messageId)).isNull();
    }

    @Test
    void sizeStaysBoundedAcrossModels() {
        VectorCache cache = new VectorCache(2);
        cache.put("lexical", UUID.randomUUID(), new float[] {1.0f});
        cache.put("openai-compatible:gemini-embedding-001", UUID.randomUUID(), new float[] {2.0f});
        cache.put("lexical", UUID.randomUUID(), new float[] {3.0f});

        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void clearRemovesEveryModel() {
        VectorCache cache = new VectorCache(8);
        cache.put("lexical", UUID.randomUUID(), new float[] {1.0f});
        cache.put("openai-compatible:gemini-embedding-001", UUID.randomUUID(), new float[] {2.0f});

        cache.clear();

        assertThat(cache.size()).isZero();
    }

    @Test
    void nullModelMessageIdOrVectorIsIgnored() {
        VectorCache cache = new VectorCache(8);
        UUID messageId = UUID.randomUUID();

        cache.put(null, messageId, new float[] {1.0f});
        cache.put("lexical", null, new float[] {2.0f});
        cache.put("lexical", messageId, null);

        assertThat(cache.size()).isZero();
    }

    @Test
    void rejectsNonPositiveMaxSize() {
        assertThatThrownBy(() -> new VectorCache(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
