package com.nexachat.summary.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Mmr {
    private static final double RELEVANCE_WEIGHT = 0.72;
    private static final double DUPLICATE_THRESHOLD = 0.78;

    private Mmr() {
    }

    static List<SummaryCandidate> select(List<SummaryCandidate> candidates, int limit) {
        if (limit <= 0 || candidates.isEmpty()) {
            return List.of();
        }
        Comparator<SummaryCandidate> stableOrder = Comparator
                .comparingLong((SummaryCandidate candidate) -> candidate.message().seq())
                .thenComparing(candidate -> safe(candidate.message().id()));
        List<SummaryCandidate> remaining = new ArrayList<>(candidates);
        remaining.sort(stableOrder);
        List<SummaryCandidate> selected = new ArrayList<>();

        while (!remaining.isEmpty() && selected.size() < limit) {
            SummaryCandidate best = null;
            double bestUtility = Double.NEGATIVE_INFINITY;
            for (SummaryCandidate candidate : remaining) {
                double similarity = selected.stream()
                        .mapToDouble(chosen -> similarity(candidate, chosen))
                        .max()
                        .orElse(0.0);
                if (similarity >= DUPLICATE_THRESHOLD) {
                    continue;
                }
                double utility = RELEVANCE_WEIGHT * candidate.score()
                        - (1.0 - RELEVANCE_WEIGHT) * similarity;
                if (best == null || utility > bestUtility + 1.0e-12) {
                    best = candidate;
                    bestUtility = utility;
                }
            }
            if (best == null) {
                break;
            }
            selected.add(best);
            remaining.remove(best);
        }
        return List.copyOf(selected);
    }

    static double similarity(SummaryCandidate left, SummaryCandidate right) {
        if (left.terms().isEmpty() || right.terms().isEmpty()) {
            return 0.0;
        }
        long intersection = left.terms().stream().distinct()
                .filter(right.terms()::contains)
                .count();
        long union = left.terms().stream().distinct().count()
                + right.terms().stream().distinct().count()
                - intersection;
        return union == 0 ? 0.0 : (double) intersection / union;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
