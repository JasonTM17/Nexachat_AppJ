package com.nexachat.rag;

import java.util.List;
import java.util.UUID;

/** A validated answer produced by the LLM tier. */
public record RagLlmAnswer(String answer, List<UUID> citationIds, String provider) {

    public RagLlmAnswer {
        citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
    }

    public RagLlmAnswer(String answer, List<UUID> citationIds) {
        this(answer, citationIds, null);
    }
}
