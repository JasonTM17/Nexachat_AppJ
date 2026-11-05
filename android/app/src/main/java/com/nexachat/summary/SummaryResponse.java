package com.nexachat.summary;

import java.util.List;

/** Immutable response model matching {@code docs/ai-summary.md}. */
public final class SummaryResponse {
    private final String summaryId;
    private final String conversationId;
    private final long fromSeq;
    private final long toSeq;
    private final int messageCount;
    private final SummaryLanguage language;
    private final String generatedBy;
    private final String model;
    private final String fallbackReason;
    private final boolean cached;
    private final String createdAt;
    private final String overview;
    private final List<SummaryItem> keyPoints;
    private final List<SummaryItem> decisions;
    private final List<SummaryActionItem> actionItems;
    private final List<SummaryItem> questions;
    private final List<String> topics;
    private final List<SummaryParticipant> participants;

    SummaryResponse() {
        this(null, null, 0L, 0L, 0, SummaryLanguage.AUTO, null, null, null,
                false, null, null, null, null, null, null, null, null);
    }

    public SummaryResponse(
            String summaryId,
            String conversationId,
            long fromSeq,
            long toSeq,
            int messageCount,
            SummaryLanguage language,
            String generatedBy,
            String model,
            String fallbackReason,
            boolean cached,
            String createdAt,
            String overview,
            List<SummaryItem> keyPoints,
            List<SummaryItem> decisions,
            List<SummaryActionItem> actionItems,
            List<SummaryItem> questions,
            List<String> topics,
            List<SummaryParticipant> participants) {
        this.summaryId = summaryId;
        this.conversationId = conversationId;
        this.fromSeq = fromSeq;
        this.toSeq = toSeq;
        this.messageCount = messageCount;
        this.language = language == null ? SummaryLanguage.AUTO : language;
        this.generatedBy = generatedBy;
        this.model = model;
        this.fallbackReason = fallbackReason;
        this.cached = cached;
        this.createdAt = createdAt;
        this.overview = overview == null ? "" : overview;
        this.keyPoints = SummaryLists.immutableCopy(keyPoints);
        this.decisions = SummaryLists.immutableCopy(decisions);
        this.actionItems = SummaryLists.immutableCopy(actionItems);
        this.questions = SummaryLists.immutableCopy(questions);
        this.topics = SummaryLists.immutableCopy(topics);
        this.participants = SummaryLists.immutableCopy(participants);
    }

    public String getSummaryId() {
        return summaryId;
    }

    public String getConversationId() {
        return conversationId;
    }

    public long getFromSeq() {
        return fromSeq;
    }

    public long getToSeq() {
        return toSeq;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public SummaryLanguage getLanguage() {
        return language == null ? SummaryLanguage.AUTO : language;
    }

    public String getGeneratedBy() {
        return generatedBy;
    }

    public String getModel() {
        return model;
    }

    public String getFallbackReason() {
        return fallbackReason;
    }

    public boolean isCached() {
        return cached;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getOverview() {
        return overview == null ? "" : overview;
    }

    public List<SummaryItem> getKeyPoints() {
        return SummaryLists.immutableCopy(keyPoints);
    }

    public List<SummaryItem> getDecisions() {
        return SummaryLists.immutableCopy(decisions);
    }

    public List<SummaryActionItem> getActionItems() {
        return SummaryLists.immutableCopy(actionItems);
    }

    public List<SummaryItem> getQuestions() {
        return SummaryLists.immutableCopy(questions);
    }

    public List<String> getTopics() {
        return SummaryLists.immutableCopy(topics);
    }

    public List<SummaryParticipant> getParticipants() {
        return SummaryLists.immutableCopy(participants);
    }

    public SummaryResponse asCached() {
        return new SummaryResponse(summaryId, conversationId, fromSeq, toSeq, messageCount,
                language, generatedBy, model, fallbackReason, true, createdAt, overview,
                keyPoints, decisions, actionItems, questions, topics, participants);
    }
}
