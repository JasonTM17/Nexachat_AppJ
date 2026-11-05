package com.nexachat.summary;

import java.util.List;
import java.util.UUID;

public record SummaryWindow(UUID conversationId, long fromSeq, long toSeq, List<SummaryMessage> messages) {

    public SummaryWindow {
        messages = messages == null ? List.of() : List.copyOf(messages);
        if (messages.isEmpty()) {
            fromSeq = 0;
            toSeq = 0;
        } else {
            fromSeq = messages.get(0).seq();
            toSeq = messages.get(messages.size() - 1).seq();
        }
    }

    public int messageCount() {
        return messages.size();
    }
}
