package com.nexachat.summary.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class CitationGuard {
    private CitationGuard() {
    }

    public static List<String> validIds(List<String> candidateIds, Set<String> allowedIds) {
        if (candidateIds == null || allowedIds == null || allowedIds.isEmpty()) {
            return List.of();
        }
        List<String> valid = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String id : candidateIds) {
            if (id != null && !id.isBlank() && allowedIds.contains(id) && seen.add(id)) {
                valid.add(id);
            }
        }
        return List.copyOf(valid);
    }
}
