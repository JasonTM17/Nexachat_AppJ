package com.nexachat.rag;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Additive answer lineage. {@code cacheType} is {@code exact} or
 * {@code semantic} only on the replaying request; {@code originMode} and the
 * generation fields describe the request that originally produced the answer.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AskProvenance(
        String originMode,
        String generationProvider,
        String cacheType,
        AskReason degradationReason,
        boolean llmAttempted) {

    public AskProvenance withCacheType(String type) {
        return new AskProvenance(originMode, generationProvider, type, degradationReason, llmAttempted);
    }

    public static AskProvenance origin(
            AskMode mode,
            String generationProvider,
            AskReason degradationReason,
            boolean llmAttempted) {
        return new AskProvenance(
                mode == null ? null : mode.name(),
                generationProvider,
                null,
                degradationReason,
                llmAttempted);
    }

    public static AskProvenance attemptOnly(boolean llmAttempted) {
        return new AskProvenance(null, null, null, null, llmAttempted);
    }
}
