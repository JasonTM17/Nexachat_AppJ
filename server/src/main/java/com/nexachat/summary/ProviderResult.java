package com.nexachat.summary;

import java.util.Objects;

public record ProviderResult(SummaryContent content, SummaryLanguage language, String model) {

    public ProviderResult {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(language, "language must not be null");
        if (language == SummaryLanguage.AUTO) {
            throw new IllegalArgumentException("Provider must resolve AUTO language");
        }
    }
}
