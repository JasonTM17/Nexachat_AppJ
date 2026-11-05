package com.nexachat.summary;

import java.util.List;

public record SummaryContent(
        String overview,
        List<SummaryItem> keyPoints,
        List<SummaryItem> decisions,
        List<SummaryActionItem> actionItems,
        List<SummaryItem> questions,
        List<String> topics,
        List<SummaryParticipant> participants) {

    public SummaryContent {
        overview = overview == null ? "" : overview;
        keyPoints = immutable(keyPoints);
        decisions = immutable(decisions);
        actionItems = immutable(actionItems);
        questions = immutable(questions);
        topics = immutable(topics);
        participants = immutable(participants);
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
