package com.nexachat.rag;

import com.nexachat.summary.engine.Tokenizer;

/** Deterministic question normalization for the exact-match cache key. */
public final class RagQuestionNormalizer {

    private RagQuestionNormalizer() {
    }

    public static String normalize(String question) {
        String folded = Tokenizer.foldForMatching(question == null ? "" : question);
        return folded.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
}
