package com.nexachat.summary;

import java.util.Objects;

public record ProviderResult(SummaryContent content, SummaryLanguage language, String model, String provider) {

    public ProviderResult {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(language, "language must not be null");
        if (language == SummaryLanguage.AUTO) {
            throw new IllegalArgumentException("Provider must resolve AUTO language");
        }
    }

    public ProviderResult(SummaryContent content, SummaryLanguage language, String model) {
        this(content, language, model, null);
    }
}
