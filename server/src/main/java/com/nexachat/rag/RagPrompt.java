package com.nexachat.rag;

import com.nexachat.summary.SummaryLanguage;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RagPrompt(
        String systemMessage,
        String userMessage,
        Map<Integer, List<UUID>> messageIdsByIndex,
        SummaryLanguage language) {

    public RagPrompt {
        messageIdsByIndex = messageIdsByIndex == null ? Map.of() : Map.copyOf(messageIdsByIndex);
    }
}
