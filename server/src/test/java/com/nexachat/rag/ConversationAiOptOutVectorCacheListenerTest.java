package com.nexachat.rag;

import com.nexachat.summary.ConversationAiOptOutEvent;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationAiOptOutVectorCacheListenerTest {

    @Test
    void optOutEventEvictsTheMembersMessageVectorsFromTheCache() {
        VectorCache cache = new VectorCache(8);
        UUID own = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        cache.put("lexical", own, new float[] {1.0f});
        cache.put("openai-compatible:gemini-embedding-001", own, new float[] {2.0f});
        cache.put("lexical", other, new float[] {3.0f});

        new ConversationAiOptOutVectorCacheListener(cache)
                .onConversationOptOut(new ConversationAiOptOutEvent(
                        UUID.randomUUID(), UUID.randomUUID(), Set.of(own)));

        assertThat(cache.get("lexical", own)).isNull();
        assertThat(cache.get("openai-compatible:gemini-embedding-001", own)).isNull();
        assertThat(cache.get("lexical", other)).isNotNull();
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void emptyMessageIdsLeaveTheCacheUntouched() {
        VectorCache cache = new VectorCache(8);
        UUID messageId = UUID.randomUUID();
        cache.put("lexical", messageId, new float[] {1.0f});

        new ConversationAiOptOutVectorCacheListener(cache)
                .onConversationOptOut(new ConversationAiOptOutEvent(
                        UUID.randomUUID(), UUID.randomUUID(), Set.of()));

        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.get("lexical", messageId)).isNotNull();
    }
}
