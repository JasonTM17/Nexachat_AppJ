package com.nexachat.summary;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SummaryResponse(
        UUID summaryId,
        UUID conversationId,
        long fromSeq,
        long toSeq,
        int messageCount,
        SummaryLanguage language,
        String generatedBy,
        String model,
        String provider,
        String fallbackReason,
        boolean cached,
        boolean contextOmitted,
        Instant createdAt,
        String overview,
        List<SummaryItem> keyPoints,
        List<SummaryItem> decisions,
        List<SummaryActionItem> actionItems,
        List<SummaryItem> questions,
        List<String> topics,
        List<SummaryParticipant> participants) {

    public SummaryResponse {
        keyPoints = immutable(keyPoints);
        decisions = immutable(decisions);
        actionItems = immutable(actionItems);
        questions = immutable(questions);
        topics = immutable(topics);
        participants = immutable(participants);
    }

    public static SummaryResponse create(
            UUID summaryId,
            SummaryWindow window,
            SummaryGeneration generated,
            boolean cached,
            boolean contextOmitted,
            Instant createdAt) {
        SummaryContent content = generated.content();
        return new SummaryResponse(
                summaryId,
                window.conversationId(),
                window.fromSeq(),
                window.toSeq(),
                window.messageCount(),
                generated.language(),
                generated.generatedBy(),
                generated.model(),
                generated.provider(),
                generated.fallbackReason() == null ? null : generated.fallbackReason().name(),
                cached,
                contextOmitted,
                createdAt,
                content.overview(),
                content.keyPoints(),
                content.decisions(),
                content.actionItems(),
                content.questions(),
                content.topics(),
                content.participants());
    }

    public SummaryResponse asCached() {
        return new SummaryResponse(summaryId, conversationId, fromSeq, toSeq, messageCount,
                language, generatedBy, model, provider, fallbackReason, true, contextOmitted, createdAt,
                overview, keyPoints, decisions, actionItems, questions, topics, participants);
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
