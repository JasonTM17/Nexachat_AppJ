package com.nexachat.rag;

import java.util.List;
import java.util.UUID;

/**
 * One prompt entry. A context item may cover several consecutive messages that
 * were merged into a single chunk. {@code messageIds} contains only ids whose
 * text was actually serialized into {@code text}; {@code primaryMessageIds} is
 * the subset that retrieval ranked as answer evidence rather than neighbour or
 * reply-parent context.
 */
public record RagContextItem(
        int index,
        List<UUID> messageIds,
        long seq,
        String senderLabel,
        String text,
        List<UUID> primaryMessageIds) {

    public RagContextItem {
        messageIds = messageIds == null ? List.of() : List.copyOf(messageIds);
        primaryMessageIds = primaryMessageIds == null ? List.of() : List.copyOf(primaryMessageIds);
    }

    public RagContextItem(int index, List<UUID> messageIds, long seq, String senderLabel, String text) {
        this(index, messageIds, seq, senderLabel, text, List.of());
    }
}
