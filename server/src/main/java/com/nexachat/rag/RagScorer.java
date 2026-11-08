package com.nexachat.rag;

import com.nexachat.summary.SummaryLanguage;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** BM25 lexical ranking, vector cosine ranking and reciprocal-rank fusion. */
public final class RagScorer {

    private RagScorer() {
    }

    public static List<ScoredMessage> lexicalRank(
            List<RagMessage> messages, String question, SummaryLanguage language) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<String> questionTerms = RagQuestionTerms.contentTerms(question, language);
        if (questionTerms.isEmpty()) {
            return List.of();
        }
        List<List<String>> documentTerms = messages.stream()
                .map(message -> RagQuestionTerms.contentTerms(message.content(), language))
                .toList();
        int documentCount = messages.size();
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (List<String> terms : documentTerms) {
            for (String term : new HashSet<>(terms)) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }
        double averageLength = documentTerms.stream()
                .mapToInt(List::size)
                .average()
                .orElse(0.0);

        double[] scores = new double[documentCount];
        for (int index = 0; index < documentCount; index++) {
            List<String> terms = documentTerms.get(index);
            Map<String, Integer> termFrequency = new HashMap<>();
            for (String term : terms) {
                termFrequency.merge(term, 1, Integer::sum);
            }
            double score = 0.0;
            for (String questionTerm : questionTerms) {
                int termCount = termFrequency.getOrDefault(questionTerm, 0);
                if (termCount == 0) {
                    continue;
                }
                int df = documentFrequency.getOrDefault(questionTerm, 0);
                double idf = Math.log(1.0 + (documentCount - df + 0.5) / (df + 0.5));
                double denominator = termCount + 1.2 * (1.0 - 0.75
                        + 0.75 * terms.size() / Math.max(1.0, averageLength));
                score += idf * (termCount * (1.2 + 1.0)) / denominator;
            }
            scores[index] = score;
        }
        return normalizeAndSort(messages, scores);
    }

    public static List<ScoredMessage> vectorRank(
            List<RagMessage> messages, Map<UUID, float[]> vectors, float[] questionVector) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        double[] scores = new double[messages.size()];
        for (int index = 0; index < messages.size(); index++) {
            float[] vector = vectors.get(messages.get(index).id());
            scores[index] = vector == null ? 0.0 : VectorCodec.cosine(questionVector, vector);
        }
        return normalizeAndSort(messages, scores);
    }

    public static List<ScoredMessage> fuse(
            List<ScoredMessage> lexical, List<ScoredMessage> vector, int k) {
        if (lexical.isEmpty() && vector.isEmpty()) {
            return List.of();
        }
        Map<UUID, ScoredMessage> byId = new LinkedHashMap<>();
        for (ScoredMessage scored : lexical) {
            byId.put(scored.message().id(), scored);
        }
        for (ScoredMessage scored : vector) {
            byId.putIfAbsent(scored.message().id(), scored);
        }
        Map<UUID, Integer> lexicalRank = ranks(lexical);
        Map<UUID, Integer> vectorRank = ranks(vector);
        int rankConstant = Math.max(1, k);
        double maxPossible = 2.0 / (rankConstant + 1.0);

        List<ScoredMessage> fused = new ArrayList<>(byId.size());
        for (Map.Entry<UUID, ScoredMessage> entry : byId.entrySet()) {
            double rrf = 0.0;
            Integer lexicalPosition = lexicalRank.get(entry.getKey());
            if (lexicalPosition != null) {
                rrf += 1.0 / (rankConstant + lexicalPosition);
            }
            Integer vectorPosition = vectorRank.get(entry.getKey());
            if (vectorPosition != null) {
                rrf += 1.0 / (rankConstant + vectorPosition);
            }
            double normalized = Math.min(1.0, rrf / maxPossible);
            fused.add(new ScoredMessage(entry.getValue().message(), normalized));
        }
        fused.sort(Comparator.comparingDouble(ScoredMessage::score).reversed()
                .thenComparing(scored -> scored.message().seq()));
        return List.copyOf(fused);
    }

    private static Map<UUID, Integer> ranks(List<ScoredMessage> scored) {
        Map<UUID, Integer> ranks = new HashMap<>();
        for (int index = 0; index < scored.size(); index++) {
            ranks.put(scored.get(index).message().id(), index + 1);
        }
        return ranks;
    }

    private static List<ScoredMessage> normalizeAndSort(List<RagMessage> messages, double[] scores) {
        double max = 0.0;
        for (double score : scores) {
            max = Math.max(max, score);
        }
        List<ScoredMessage> scored = new ArrayList<>(messages.size());
        for (int index = 0; index < messages.size(); index++) {
            double normalized = max <= 0.0 ? 0.0 : scores[index] / max;
            scored.add(new ScoredMessage(messages.get(index), normalized));
        }
        scored.sort(Comparator.comparingDouble(ScoredMessage::score).reversed()
                .thenComparing(item -> item.message().seq()));
        return List.copyOf(scored);
    }
}
