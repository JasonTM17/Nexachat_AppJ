package com.nexachat.summary;

import java.time.Instant;
import java.util.UUID;

public record SummaryCacheEntry(
        UUID id,
        long fromSeq,
        long toSeq,
        String contentHash,
        String cacheProvider,
        SummaryLanguage requestedLanguage,
        SummaryCachePayload payload,
        Instant createdAt,
        Instant expiresAt) {
}
