package com.nexachat.rag;

import java.util.List;
import java.util.UUID;

/**
 * One prompt entry. A context item may cover several consecutive messages that
 * were merged into a single chunk; every covered message id is citable.
 */
public record RagContextItem(
        int index,
        List<UUID> messageIds,
        long seq,
        String senderLabel,
        String text) {

    public RagContextItem {
        messageIds = messageIds == null ? List.of() : List.copyOf(messageIds);
    }
}
