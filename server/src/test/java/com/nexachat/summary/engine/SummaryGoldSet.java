package com.nexachat.summary.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

final class SummaryGoldSet {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalExtractiveSummaryProvider PROVIDER = new LocalExtractiveSummaryProvider();

    private SummaryGoldSet() {
    }

    static List<Conversation> load() {
        URL directory = SummaryGoldSet.class.getResource("/summary-gold");
        if (directory == null) {
            throw new IllegalStateException("Summary gold resources are missing");
        }
        try (Stream<Path> files = Files.list(Path.of(directory.toURI()))) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .map(SummaryGoldSet::read)
                    .toList();
        } catch (IOException | URISyntaxException exception) {
            throw new IllegalStateException("Could not load summary gold resources", exception);
        }
    }

    static List<Conversation> split(String split) {
        return load().stream().filter(conversation -> conversation.split().equals(split)).toList();
    }

    static Evaluation evaluate(List<Conversation> conversations) {
        Totals totals = new Totals();
        Map<String, Totals> byLanguage = new LinkedHashMap<>();
        for (Conversation conversation : conversations) {
            LocalSummary summary = PROVIDER.summarize(conversation.window(), SummaryLanguage.AUTO);
            Totals languageTotals = byLanguage.computeIfAbsent(conversation.language().name(), ignored -> new Totals());
            compare(totals, conversation, summary);
            compare(languageTotals, conversation, summary);
        }
        return new Evaluation(totals.metrics(), byLanguage.entrySet().stream()
                .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue().metrics()),
                        LinkedHashMap::putAll));
    }

    static void print(String split, List<Conversation> conversations) {
        Evaluation evaluation = evaluate(conversations);
        System.out.println("split=" + split + " conversations=" + conversations.size());
        System.out.println("language decision(P/R) action(P/R) question(P/R) keyPoint(P/R) citation-validity");
        printRow("ALL", evaluation.overall());
        evaluation.byLanguage().forEach(SummaryGoldSet::printRow);
    }

    private static void printRow(String language, Metrics metrics) {
        System.out.printf("%-8s %5.2f/%-5.2f %5.2f/%-5.2f %5.2f/%-5.2f %5.2f/%-5.2f %5.2f%n",
                language,
                metrics.decisions().precision(), metrics.decisions().recall(),
                metrics.actions().precision(), metrics.actions().recall(),
                metrics.questions().precision(), metrics.questions().recall(),
                metrics.keyPoints().precision(), metrics.keyPoints().recall(),
                metrics.citationValidity());
    }

    private static void compare(Totals totals, Conversation conversation, LocalSummary summary) {
        totals.decisions.add(conversation.expected().decisions(), itemIds(summary.decisions()));
        totals.actions.add(conversation.expected().actions(), actionIds(summary.actionItems()));
        totals.questions.add(conversation.expected().questions(), itemIds(summary.questions()));
        totals.keyPoints.add(conversation.expected().keyPoints(), itemIds(summary.keyPoints()));

        Set<String> inputIds = conversation.window().messages().stream().map(SummaryMessage::id).collect(
                HashSet::new, Set::add, Set::addAll);
        summary.keyPoints().forEach(item -> totals.checkCitation(item.text(), item.messageIds(), inputIds));
        summary.decisions().forEach(item -> totals.checkCitation(item.text(), item.messageIds(), inputIds));
        summary.questions().forEach(item -> totals.checkCitation(item.text(), item.messageIds(), inputIds));
        summary.actionItems().forEach(item -> totals.checkCitation(item.text(), item.messageIds(), inputIds));
    }

    private static Set<String> itemIds(List<LocalSummary.Item> items) {
        return items.stream().flatMap(item -> item.messageIds().stream()).collect(HashSet::new, Set::add, Set::addAll);
    }

    private static Set<String> actionIds(List<LocalSummary.ActionItem> items) {
        return items.stream().flatMap(item -> item.messageIds().stream()).collect(HashSet::new, Set::add, Set::addAll);
    }

    private static Conversation read(Path path) {
        try {
            JsonNode root = JSON.readTree(path.toFile());
            List<SummaryMessage> messages = new ArrayList<>();
            for (JsonNode node : root.path("messages")) {
                messages.add(new SummaryMessage(
                        node.path("id").asText(),
                        node.path("seq").asLong(),
                        node.path("senderId").asText(null),
                        node.path("senderName").asText(null),
                        node.path("text").asText()));
            }
            Set<String> messageIds = messages.stream().map(SummaryMessage::id).collect(HashSet::new, Set::add, Set::addAll);
            JsonNode expected = root.path("expected");
            Expected labels = new Expected(
                    strings(expected.path("decisions")),
                    strings(expected.path("actions")),
                    strings(expected.path("questions")),
                    strings(expected.path("keyPoints")));
            Set<String> allExpected = new HashSet<>();
            allExpected.addAll(labels.decisions());
            allExpected.addAll(labels.actions());
            allExpected.addAll(labels.questions());
            allExpected.addAll(labels.keyPoints());
            if (!messageIds.containsAll(allExpected)) {
                throw new IllegalArgumentException("Gold labels reference a message outside " + path.getFileName());
            }
            return new Conversation(
                    root.path("id").asText(),
                    root.path("split").asText(),
                    SummaryLanguage.valueOf(root.path("language").asText()),
                    new SummaryWindow(messages),
                    labels);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read summary gold file " + path.getFileName(), exception);
        }
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> values = new HashSet<>();
        array.forEach(value -> values.add(value.asText()));
        return Set.copyOf(values);
    }

    record Conversation(String id, String split, SummaryLanguage language, SummaryWindow window, Expected expected) {
    }

    record Expected(Set<String> decisions, Set<String> actions, Set<String> questions, Set<String> keyPoints) {
    }

    record Evaluation(Metrics overall, Map<String, Metrics> byLanguage) {
    }

    record Metrics(Counts decisions, Counts actions, Counts questions, Counts keyPoints, double citationValidity) {
    }

    record Counts(int truePositive, int falsePositive, int falseNegative) {
        double precision() {
            return ratio(truePositive, truePositive + falsePositive);
        }

        double recall() {
            return ratio(truePositive, truePositive + falseNegative);
        }

        private static double ratio(int numerator, int denominator) {
            return denominator == 0 ? 1.0 : (double) numerator / denominator;
        }
    }

    private static final class Totals {
        private final CountAccumulator decisions = new CountAccumulator();
        private final CountAccumulator actions = new CountAccumulator();
        private final CountAccumulator questions = new CountAccumulator();
        private final CountAccumulator keyPoints = new CountAccumulator();
        private int validCitations;
        private int citationItems;

        private void checkCitation(String text, List<String> ids, Set<String> inputIds) {
            citationItems++;
            if (text != null && !text.isBlank() && ids != null && !ids.isEmpty() && inputIds.containsAll(ids)) {
                validCitations++;
            }
        }

        private Metrics metrics() {
            return new Metrics(decisions.counts(), actions.counts(), questions.counts(), keyPoints.counts(),
                    citationItems == 0 ? 1.0 : (double) validCitations / citationItems);
        }
    }

    private static final class CountAccumulator {
        private int truePositive;
        private int falsePositive;
        private int falseNegative;

        private void add(Set<String> expected, Set<String> predicted) {
            truePositive += (int) expected.stream().filter(predicted::contains).count();
            falsePositive += (int) predicted.stream().filter(id -> !expected.contains(id)).count();
            falseNegative += (int) expected.stream().filter(id -> !predicted.contains(id)).count();
        }

        private Counts counts() {
            return new Counts(truePositive, falsePositive, falseNegative);
        }
    }
}
