package com.nexachat.rag;

import java.util.List;
import java.util.UUID;

/** A validated answer produced by the LLM tier. */
public record RagLlmAnswer(String answer, List<UUID> citationIds) {

    public RagLlmAnswer {
        citationIds = citationIds == null ? List.of() : List.copyOf(citationIds);
    }
}
