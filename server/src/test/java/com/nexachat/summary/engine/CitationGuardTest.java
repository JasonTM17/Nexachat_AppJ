package com.nexachat.summary.engine;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CitationGuardTest {
    @Test
    void retainsOnlyUniqueNonemptyIdsFromTheInputWindow() {
        List<String> result = CitationGuard.validIds(
                Arrays.asList("m1", "outside", "m1", null, " ", "m2"),
                Set.of("m1", "m2"));
        assertEquals(List.of("m1", "m2"), result);
    }

    @Test
    void returnsNoCitationsWhenTheInputWindowHasNoAllowedIds() {
        assertEquals(List.of(), CitationGuard.validIds(List.of("m1"), Set.of()));
        assertEquals(List.of(), CitationGuard.validIds(List.of("m1"), null));
    }
}
