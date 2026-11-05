package com.nexachat.summary;

public record SummaryCachePayload(
        SummaryLanguage language,
        String generatedBy,
        String model,
        String fallbackReason,
        SummaryContent content) {

    public SummaryGeneration toGeneration() {
        SummaryFallbackReason reason = fallbackReason == null
                ? null
                : SummaryFallbackReason.valueOf(fallbackReason);
        return new SummaryGeneration(content, language, generatedBy, model, reason);
    }

    public static SummaryCachePayload from(SummaryGeneration generation) {
        return new SummaryCachePayload(
                generation.language(),
                generation.generatedBy(),
                generation.model(),
                generation.fallbackReason() == null ? null : generation.fallbackReason().name(),
                generation.content());
    }
}
