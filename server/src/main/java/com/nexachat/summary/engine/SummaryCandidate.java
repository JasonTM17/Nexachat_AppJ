package com.nexachat.summary.engine;

import java.util.List;

public record SummaryCandidate(
        SummaryMessage message,
        List<String> terms,
        List<String> originalTerms,
        double score
) {
    public SummaryCandidate {
        terms = terms == null ? List.of() : List.copyOf(terms);
        originalTerms = originalTerms == null ? List.of() : List.copyOf(originalTerms);
    }
}
