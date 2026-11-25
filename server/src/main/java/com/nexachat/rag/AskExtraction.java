package com.nexachat.rag;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * Additive metadata for a precise extractive answer. {@code start} and
 * {@code end} are UTF-16 offsets in the cited source message, with {@code end}
 * exclusive. {@code kind} is {@code EXACT_VALUE} for a value lookup or
 * {@code EVIDENCE_SPAN} for the supporting sentence/span.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AskExtraction(
        String kind,
        UUID sourceMessageId,
        int startOffset,
        int endOffset,
        boolean truncated,
        String valueType) {
}
