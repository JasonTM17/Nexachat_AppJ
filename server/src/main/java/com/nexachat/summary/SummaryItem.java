package com.nexachat.summary;

import java.util.List;
import java.util.UUID;

public record SummaryItem(String text, List<UUID> messageIds) {

    public SummaryItem {
        text = text == null ? "" : text;
        messageIds = messageIds == null ? List.of() : List.copyOf(messageIds);
    }
}
