package com.nexachat.summary.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TopicExtractor {
    private TopicExtractor() {
    }

    static List<String> extract(List<SummaryCandidate> candidates, SummaryLanguage language, int limit) {
        if (limit <= 0 || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, Topic> topics = new HashMap<>();
        for (SummaryCandidate candidate : candidates) {
            List<String> folded = candidate.terms();
            List<String> original = candidate.originalTerms();
            Set<String> seenInMessage = new HashSet<>();
            for (int size = 1; size <= 3; size++) {
                int ngramSize = size;
                for (int start = 0; start + size <= folded.size(); start++) {
                    List<String> phraseTerms = folded.subList(start, start + size);
                    if (phraseTerms.stream().anyMatch(term -> term.length() < 2
                            || term.codePoints().allMatch(Character::isDigit)
                            || StopWords.contains(term, language))) {
                        continue;
                    }
                    String key = String.join(" ", phraseTerms);
                    String label = String.join(" ", original.subList(start, start + size));
                    Topic topic = topics.computeIfAbsent(key, ignored -> new Topic(key, label, ngramSize));
                    topic.frequency++;
                    topic.firstSeq = Math.min(topic.firstSeq, candidate.message().seq());
                    seenInMessage.add(key);
                }
            }
            for (String key : seenInMessage) {
                topics.get(key).documentFrequency++;
            }
        }

        List<Topic> ranked = new ArrayList<>(topics.values());
        int messageCount = Math.max(1, candidates.size());
        ranked.sort(Comparator.comparingDouble((Topic topic) -> topic.score(messageCount)).reversed()
                .thenComparingLong(topic -> topic.firstSeq)
                .thenComparing(topic -> topic.key));

        List<String> result = new ArrayList<>();
        Set<String> selectedKeys = new HashSet<>();
        for (Topic topic : ranked) {
            if (result.size() == limit) {
                break;
            }
            boolean redundant = selectedKeys.stream()
                    .anyMatch(selected -> isSubsetPhrase(topic.key, selected) || isSubsetPhrase(selected, topic.key));
            if (!redundant) {
                result.add(topic.label);
                selectedKeys.add(topic.key);
            }
        }
        return List.copyOf(result);
    }

    private static boolean isSubsetPhrase(String possibleSubset, String possibleSuperset) {
        List<String> subset = List.of(possibleSubset.split(" "));
        List<String> superset = List.of(possibleSuperset.split(" "));
        return subset.size() < superset.size() && superset.containsAll(subset);
    }

    private static final class Topic {
        private final String key;
        private final String label;
        private final int size;
        private int frequency;
        private int documentFrequency;
        private long firstSeq = Long.MAX_VALUE;

        private Topic(String key, String label, int size) {
            this.key = key;
            this.label = label;
            this.size = size;
        }

        private double score(int messageCount) {
            double idf = 1.0 + Math.log(1.0 + messageCount / (documentFrequency + 1.0));
            return frequency * idf * (1.0 + 0.12 * (size - 1));
        }
    }
}
