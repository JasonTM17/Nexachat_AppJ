package com.nexachat.rag;

/** A message with its normalized fused retrieval score in [0, 1]. */
public record ScoredMessage(RagMessage message, double score) {
}
