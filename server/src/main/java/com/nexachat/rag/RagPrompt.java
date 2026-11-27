package com.nexachat.rag;

import com.nexachat.summary.SummaryLanguage;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RagPrompt(
        String systemMessage,
        String userMessage,
        Map<Integer, List<UUID>> messageIdsByIndex,
        SummaryLanguage language,
        Map<Integer, String> quoteSources) {

    public RagPrompt {
        messageIdsByIndex = messageIdsByIndex == null ? Map.of() : Map.copyOf(messageIdsByIndex);
        quoteSources = Map.copyOf(quoteSources);
    }

    public RagPrompt(String systemMessage, String userMessage, Map<Integer, List<UUID>> messageIdsByIndex,
                     SummaryLanguage language) {
        this(systemMessage, userMessage, messageIdsByIndex, language, Map.of());
    }
}
