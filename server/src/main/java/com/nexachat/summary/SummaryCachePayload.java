package com.nexachat.summary;

public record SummaryCachePayload(
        SummaryLanguage language,
        String generatedBy,
        String model,
        String fallbackReason,
        SummaryContent content,
        String provider) {

    public SummaryCachePayload(
            SummaryLanguage language,
            String generatedBy,
            String model,
            String fallbackReason,
            SummaryContent content) {
        this(language, generatedBy, model, fallbackReason, content, null);
    }

    public SummaryGeneration toGeneration() {
        SummaryFallbackReason reason = fallbackReason == null
                ? null
                : SummaryFallbackReason.valueOf(fallbackReason);
        return new SummaryGeneration(content, language, generatedBy, model, reason, provider);
    }

    public static SummaryCachePayload from(SummaryGeneration generation) {
        return new SummaryCachePayload(
                generation.language(),
                generation.generatedBy(),
                generation.model(),
                generation.fallbackReason() == null ? null : generation.fallbackReason().name(),
                generation.content(),
                generation.provider());
    }
}
