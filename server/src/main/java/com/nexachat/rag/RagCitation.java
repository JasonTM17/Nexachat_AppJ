package com.nexachat.rag;

import java.util.UUID;

/** A message a cached or generated answer cites. */
public record RagCitation(UUID messageId, long seq, String snippet) {
}
