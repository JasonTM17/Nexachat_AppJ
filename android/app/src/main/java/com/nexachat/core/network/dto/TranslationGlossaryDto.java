package com.nexachat.core.network.dto;

import androidx.annotation.Nullable;
import java.util.List;

/**
 * Reflective Moshi bean for the per-member conversation glossary. Doubles as
 * the PUT body ({@code terms} only is read server-side; {@code conversationId}
 * is ignored on write).
 */
public final class TranslationGlossaryDto {

    /** One glossary term; {@code replacement} null/empty means keep verbatim. */
    public static final class Term {
        private String term;
        private String replacement;

        public Term() {
        }

        public Term(String term, @Nullable String replacement) {
            this.term = term;
            this.replacement = replacement;
        }

        @Nullable public String getTerm() { return term; }
        @Nullable public String getReplacement() { return replacement; }
    }

    private String conversationId;
    private List<Term> terms;

    public TranslationGlossaryDto() {
    }

    public TranslationGlossaryDto(List<Term> terms) {
        this.terms = terms;
    }

    @Nullable public String getConversationId() { return conversationId; }
    @Nullable public List<Term> getTerms() { return terms; }
}
