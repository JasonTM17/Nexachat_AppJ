package com.nexachat.summary;

public record PreparedSummaryProvider(
        SummaryProvider provider,
        String cacheProvider,
        SummaryFallbackReason fallbackReason) {
}
