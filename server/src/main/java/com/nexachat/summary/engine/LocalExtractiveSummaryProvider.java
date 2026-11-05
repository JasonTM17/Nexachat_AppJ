package com.nexachat.summary.engine;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public final class LocalExtractiveSummaryProvider implements com.nexachat.summary.SummaryProvider {
    public static final String PROVIDER_ID = "LOCAL";
    private static final int MAX_KEY_POINTS = 3;
    private static final int MAX_CLASSIFIED_ITEMS = 5;
    private static final int MAX_TOPICS = 5;

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public com.nexachat.summary.ProviderResult summarize(
            com.nexachat.summary.SummaryWindow window,
            com.nexachat.summary.SummaryLanguage requestedLanguage) {
        List<SummaryMessage> messages = window == null ? List.of() : window.messages().stream()
                .map(message -> new SummaryMessage(
                        message.id() == null ? null : message.id().toString(),
                        message.seq(),
                        message.senderId() == null ? null : message.senderId().toString(),
                        message.senderName(),
                        message.text(),
                        message.createdAt(),
                        message.editedAt(),
                        SummarySignals.none()))
                .toList();
        SummaryLanguage language = requestedLanguage == null
                ? SummaryLanguage.AUTO
                : SummaryLanguage.valueOf(requestedLanguage.name());
        LocalSummary summary = summarize(new SummaryWindow(messages), language);
        return new com.nexachat.summary.ProviderResult(
                new com.nexachat.summary.SummaryContent(
                        summary.overview(),
                        toApiItems(summary.keyPoints()),
                        toApiItems(summary.decisions()),
                        summary.actionItems().stream()
                                .map(item -> new com.nexachat.summary.SummaryActionItem(
                                        item.text(),
                                        item.ownerUserId() == null ? null : UUID.fromString(item.ownerUserId()),
                                        item.dueHint(),
                                        toUuids(item.messageIds())))
                                .toList(),
                        toApiItems(summary.questions()),
                        summary.topics(),
                        summary.participants().stream()
                                .map(participant -> new com.nexachat.summary.SummaryParticipant(
                                        UUID.fromString(participant.userId()),
                                        participant.displayName(),
                                        participant.messageCount()))
                                .toList()),
                com.nexachat.summary.SummaryLanguage.valueOf(summary.language().name()),
                null);
    }

    public LocalSummary summarize(SummaryWindow window, SummaryLanguage requestedLanguage) {
        List<SummaryMessage> orderedMessages = orderedUsableMessages(window);
        SummaryLanguage language = requestedLanguage == null || requestedLanguage == SummaryLanguage.AUTO
                ? LanguageDetector.detect(new SummaryWindow(orderedMessages))
                : requestedLanguage;
        List<SummaryMessage> candidates = orderedMessages.stream()
                .filter(message -> !NoiseFilter.isNoise(message.text()))
                .toList();
        List<SummaryCandidate> scored = MessageScorer.score(candidates, language);
        Set<String> allowedIds = orderedMessages.stream()
                .map(SummaryMessage::id)
                .collect(Collectors.toSet());

        List<SummaryCandidate> keyPoints = Mmr.select(scored, MAX_KEY_POINTS);
        List<SummaryCandidate> decisions = Mmr.select(scored.stream()
                .filter(candidate -> CueDetector.isDecision(candidate.message()))
                .toList(), MAX_CLASSIFIED_ITEMS);
        List<SummaryCandidate> actions = Mmr.select(scored.stream()
                .filter(candidate -> CueDetector.isAction(candidate.message()))
                .toList(), MAX_CLASSIFIED_ITEMS);
        List<SummaryCandidate> questions = Mmr.select(scored.stream()
                .filter(candidate -> CueDetector.isUnansweredQuestion(candidate.message(), orderedMessages))
                .toList(), MAX_CLASSIFIED_ITEMS);

        List<LocalSummary.Item> keyPointItems = items(keyPoints, allowedIds);
        List<LocalSummary.Item> decisionItems = items(decisions, allowedIds);
        List<LocalSummary.ActionItem> actionItems = actions.stream()
                .map(candidate -> {
                    List<String> ids = CitationGuard.validIds(List.of(candidate.message().id()), allowedIds);
                    if (ids.isEmpty()) {
                        return null;
                    }
                    return new LocalSummary.ActionItem(
                            candidate.message().text(),
                            CueDetector.ownerUserId(candidate.message(), orderedMessages),
                            DueHintExtractor.extract(candidate.message().text()),
                            ids
                    );
                })
                .filter(item -> item != null)
                .toList();
        List<LocalSummary.Item> questionItems = items(questions, allowedIds);
        List<String> topics = TopicExtractor.extract(scored, language, MAX_TOPICS);

        return new LocalSummary(
                language,
                OverviewComposer.compose(keyPoints),
                keyPointItems,
                decisionItems,
                actionItems,
                questionItems,
                topics,
                participants(orderedMessages)
        );
    }

    private static List<SummaryMessage> orderedUsableMessages(SummaryWindow window) {
        if (window == null || window.messages() == null) {
            return List.of();
        }
        Map<String, SummaryMessage> unique = new LinkedHashMap<>();
        window.messages().stream()
                .filter(message -> message != null)
                .filter(message -> message.id() != null && !message.id().isBlank())
                .filter(message -> message.text() != null && !message.text().isBlank())
                .sorted(Comparator.comparingLong(SummaryMessage::seq).thenComparing(SummaryMessage::id))
                .forEach(message -> unique.putIfAbsent(message.id(), message));
        return List.copyOf(unique.values());
    }

    private static List<LocalSummary.Item> items(List<SummaryCandidate> candidates, Set<String> allowedIds) {
        List<LocalSummary.Item> items = new ArrayList<>();
        for (SummaryCandidate candidate : candidates) {
            List<String> ids = CitationGuard.validIds(List.of(candidate.message().id()), allowedIds);
            if (!ids.isEmpty()) {
                items.add(new LocalSummary.Item(candidate.message().text(), ids));
            }
        }
        return List.copyOf(items);
    }

    private static List<com.nexachat.summary.SummaryItem> toApiItems(List<LocalSummary.Item> items) {
        return items.stream()
                .map(item -> new com.nexachat.summary.SummaryItem(item.text(), toUuids(item.messageIds())))
                .toList();
    }

    private static List<UUID> toUuids(List<String> ids) {
        return ids.stream().map(UUID::fromString).toList();
    }

    private static List<LocalSummary.Participant> participants(List<SummaryMessage> messages) {
        Map<String, ParticipantCount> counts = new TreeMap<>();
        for (SummaryMessage message : messages) {
            if (message.senderId() == null || message.senderId().isBlank()) {
                continue;
            }
            ParticipantCount count = counts.computeIfAbsent(
                    message.senderId(),
                    ignored -> new ParticipantCount(message.senderName())
            );
            count.messageCount++;
            if ((count.displayName == null || count.displayName.isBlank())
                    && message.senderName() != null && !message.senderName().isBlank()) {
                count.displayName = message.senderName();
            }
        }
        return counts.entrySet().stream()
                .map(entry -> new LocalSummary.Participant(
                        entry.getKey(),
                        entry.getValue().displayName == null ? "" : entry.getValue().displayName,
                        entry.getValue().messageCount
                ))
                .toList();
    }

    private static final class ParticipantCount {
        private String displayName;
        private int messageCount;

        private ParticipantCount(String displayName) {
            this.displayName = displayName;
        }
    }
}
