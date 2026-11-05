package com.nexachat.summary.llm;

import com.nexachat.summary.SummaryLanguage;
import com.nexachat.summary.SummaryParticipant;

import java.util.List;
import java.util.Map;
import java.util.UUID;

record Prompt(
        String systemMessage,
        String userMessage,
        Map<Integer, UUID> messageIdsByIndex,
        Map<String, UUID> senderIdsByLabel,
        List<SummaryParticipant> participants,
        SummaryLanguage language) {

    Prompt {
        messageIdsByIndex = Map.copyOf(messageIdsByIndex);
        senderIdsByLabel = Map.copyOf(senderIdsByLabel);
        participants = List.copyOf(participants);
    }
}
