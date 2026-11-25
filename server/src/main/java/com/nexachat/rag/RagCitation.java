package com.nexachat.rag;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * A message a cached or generated answer cites. {@code role} is additive:
 * {@code PRIMARY} means the message itself was ranked as answer evidence, while
 * {@code CONTEXT} means it entered the prompt as a neighbour or reply parent.
 * Older cache rows may not carry a role.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RagCitation(UUID messageId, long seq, String snippet, Role role) {

    public RagCitation(UUID messageId, long seq, String snippet) {
        this(messageId, seq, snippet, null);
    }

    public enum Role {
        PRIMARY,
        CONTEXT
    }
}
