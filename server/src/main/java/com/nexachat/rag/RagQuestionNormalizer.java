package com.nexachat.rag;

import com.nexachat.summary.engine.Tokenizer;

import java.util.Locale;

/** Question normalization for lexical matching and exact cache keys. */
public final class RagQuestionNormalizer {

    private RagQuestionNormalizer() {
    }

    public static String normalize(String question) {
        String folded = Tokenizer.foldForMatching(question == null ? "" : question);
        return folded.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    static String normalizeForCache(String text) {
        // Accents and punctuation can change a request's intent even when its search tokens match.
        return Tokenizer.nfc(text).toLowerCase(Locale.ROOT).replaceAll("(?U)\\s+", " ").strip();
    }
}
