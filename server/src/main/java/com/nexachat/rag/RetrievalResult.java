package com.nexachat.rag;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RetrievalResult(
        List<ScoredMessage> ranked,
        RagMessage topMessage,
        List<RagContextItem> context,
        double bestScore,
        int inputChars,
        Map<UUID, RagMessage> messages,
        boolean optedOutEvidenceOverlap,
        List<RagMessage> lookupCandidates) {

    public RetrievalResult {
        ranked = ranked == null ? List.of() : List.copyOf(ranked);
        context = context == null ? List.of() : List.copyOf(context);
        messages = messages == null ? Map.of() : Map.copyOf(messages);
        lookupCandidates = lookupCandidates == null ? List.of() : List.copyOf(lookupCandidates);
    }

    public RetrievalResult(
            List<ScoredMessage> ranked,
            RagMessage topMessage,
            List<RagContextItem> context,
            double bestScore,
            int inputChars,
            Map<UUID, RagMessage> messages,
            boolean optedOutEvidenceOverlap) {
        this(ranked, topMessage, context, bestScore, inputChars, messages, optedOutEvidenceOverlap, List.of());
    }

    public RetrievalResult(
            List<ScoredMessage> ranked,
            RagMessage topMessage,
            List<RagContextItem> context,
            double bestScore,
            int inputChars,
            Map<UUID, RagMessage> messages) {
        this(ranked, topMessage, context, bestScore, inputChars, messages, false);
    }

    public static RetrievalResult empty() {
        return new RetrievalResult(List.of(), null, List.of(), 0.0, 0, Map.of(), false);
    }

    public boolean isEmpty() {
        return ranked.isEmpty();
    }
}
