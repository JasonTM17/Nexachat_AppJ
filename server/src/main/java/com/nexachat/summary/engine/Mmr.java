package com.nexachat.summary.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Mmr {
    private static final double RELEVANCE_WEIGHT = 0.72;
    private static final double DUPLICATE_THRESHOLD = 0.78;

    private Mmr() {
    }

    public static List<SummaryCandidate> select(List<SummaryCandidate> candidates, int limit) {
        return select(candidates, limit, RELEVANCE_WEIGHT);
    }

    public static List<SummaryCandidate> select(List<SummaryCandidate> candidates, int limit, double lambda) {
        if (limit <= 0 || candidates.isEmpty()) {
            return List.of();
        }
        double relevanceWeight = Math.max(0.0, Math.min(1.0, lambda));
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
                double utility = relevanceWeight * candidate.score()
                        - (1.0 - relevanceWeight) * similarity;
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

    public static double similarity(SummaryCandidate left, SummaryCandidate right) {
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
