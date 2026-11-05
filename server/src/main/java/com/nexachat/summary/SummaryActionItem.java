package com.nexachat.summary;

import java.util.List;
import java.util.UUID;

public record SummaryActionItem(String text, UUID ownerUserId, String dueHint, List<UUID> messageIds) {

    public SummaryActionItem {
        text = text == null ? "" : text;
        messageIds = messageIds == null ? List.of() : List.copyOf(messageIds);
    }
}
