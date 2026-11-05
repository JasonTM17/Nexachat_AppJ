package com.nexachat.summary.engine;

import java.util.List;

record SummaryCandidate(
        SummaryMessage message,
        List<String> terms,
        List<String> originalTerms,
        double score
) {
    SummaryCandidate {
        terms = List.copyOf(terms);
        originalTerms = List.copyOf(originalTerms);
    }
}
