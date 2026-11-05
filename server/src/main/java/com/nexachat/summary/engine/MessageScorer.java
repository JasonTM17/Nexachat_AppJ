package com.nexachat.summary.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MessageScorer {
    private MessageScorer() {
    }

    static List<SummaryCandidate> score(List<SummaryMessage> messages, SummaryLanguage language) {
        if (messages.isEmpty()) {
            return List.of();
        }

        List<List<Tokenizer.Token>> tokenData = messages.stream()
                .map(message -> Tokenizer.tokenData(message.text()))
                .toList();
        Map<String, Integer> documentFrequency = new HashMap<>();
        Map<String, Integer> senderFrequency = new HashMap<>();
        for (int i = 0; i < messages.size(); i++) {
            Set<String> messageTerms = new HashSet<>();
            for (Tokenizer.Token token : tokenData.get(i)) {
                if (isContentToken(token.folded(), language)) {
                    messageTerms.add(token.folded());
                }
            }
            messageTerms.forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
            String senderId = messages.get(i).senderId();
            if (senderId != null && !senderId.isBlank()) {
                senderFrequency.merge(senderId, 1, Integer::sum);
            }
        }

        double[] rawScores = new double[messages.size()];
        List<List<String>> termsByMessage = new ArrayList<>(messages.size());
        List<List<String>> originalsByMessage = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            List<String> terms = new ArrayList<>();
            List<String> originals = new ArrayList<>();
            Map<String, Integer> termFrequency = new HashMap<>();
            for (Tokenizer.Token token : tokenData.get(i)) {
                if (isContentToken(token.folded(), language)) {
                    terms.add(token.folded());
                    originals.add(token.original());
                    termFrequency.merge(token.folded(), 1, Integer::sum);
                }
            }
            termsByMessage.add(terms);
            originalsByMessage.add(originals);

            double lexicalScore = 0;
            for (Map.Entry<String, Integer> entry : termFrequency.entrySet()) {
                int df = documentFrequency.getOrDefault(entry.getKey(), 1);
                double idf = 1.0 + Math.log(1.0 + (messages.size() + 1.0) / (df + 1.0));
                lexicalScore += (1.0 + Math.log(entry.getValue())) * idf;
            }
            if (!terms.isEmpty()) {
                lexicalScore /= Math.sqrt(terms.size());
            }

            double position = messages.size() <= 1 ? 1.0 : 1.15 - 0.15 * i / (messages.size() - 1.0);
            String senderId = messages.get(i).senderId();
            int senderCount = senderId == null ? messages.size() : senderFrequency.getOrDefault(senderId, messages.size());
            double diversity = 1.0 + 0.18 / Math.sqrt(Math.max(1, senderCount));
            SummarySignals signals = messages.get(i).signals();
            double interactionBoost = (signals.pinned() ? 0.10 : 0.0)
                    + (signals.reactionCount() > 0 ? 0.05 : 0.0)
                    + (signals.replyToMessageId() != null && !signals.replyToMessageId().isBlank() ? 0.03 : 0.0);
            rawScores[i] = lexicalScore * position * diversity + interactionBoost;
        }

        double max = 0.0;
        for (double raw : rawScores) {
            max = Math.max(max, raw);
        }
        List<SummaryCandidate> result = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            double normalizedScore = max == 0.0 ? 0.0 : rawScores[i] / max;
            result.add(new SummaryCandidate(
                    messages.get(i),
                    termsByMessage.get(i),
                    originalsByMessage.get(i),
                    normalizedScore
            ));
        }
        return List.copyOf(result);
    }

    private static boolean isContentToken(String token, SummaryLanguage language) {
        return token.length() > 1
                && !token.codePoints().allMatch(Character::isDigit)
                && !StopWords.contains(token, language);
    }
}
