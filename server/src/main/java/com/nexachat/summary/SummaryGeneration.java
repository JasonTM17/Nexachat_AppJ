package com.nexachat.summary;

import java.util.Objects;

public record SummaryGeneration(
        SummaryContent content,
        SummaryLanguage language,
        String generatedBy,
        String model,
        SummaryFallbackReason fallbackReason,
        String provider) {

    public SummaryGeneration {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(language, "language must not be null");
        Objects.requireNonNull(generatedBy, "generatedBy must not be null");
        if (language == SummaryLanguage.AUTO) {
            throw new IllegalArgumentException("Generated language must be resolved");
        }
    }

    public SummaryGeneration(
            SummaryContent content,
            SummaryLanguage language,
            String generatedBy,
            String model,
            SummaryFallbackReason fallbackReason) {
        this(content, language, generatedBy, model, fallbackReason, null);
    }
}
