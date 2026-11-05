package com.nexachat.summary.engine;

import java.util.List;

public record LocalSummary(
        SummaryLanguage language,
        String overview,
        List<Item> keyPoints,
        List<Item> decisions,
        List<ActionItem> actionItems,
        List<Item> questions,
        List<String> topics,
        List<Participant> participants
) {
    public LocalSummary {
        keyPoints = List.copyOf(keyPoints);
        decisions = List.copyOf(decisions);
        actionItems = List.copyOf(actionItems);
        questions = List.copyOf(questions);
        topics = List.copyOf(topics);
        participants = List.copyOf(participants);
    }

    public record Item(String text, List<String> messageIds) {
        public Item {
            messageIds = List.copyOf(messageIds);
        }
    }

    public record ActionItem(String text, String ownerUserId, String dueHint, List<String> messageIds) {
        public ActionItem {
            messageIds = List.copyOf(messageIds);
        }
    }

    public record Participant(String userId, String displayName, int messageCount) {
    }
}
