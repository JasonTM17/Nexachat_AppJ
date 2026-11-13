package com.nexachat.feature.inbox;

import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ActiveNowStripUpdateRulesTest {

    @Test
    public void needsStructuralRebuild_whenPeerOrderOrMembershipChanges() {
        List<String> before = List.of("u1", "u2");
        assertFalse(ActiveNowStripUpdateRules.needsStructuralRebuild(before, List.of("u1", "u2")));
        assertTrue(ActiveNowStripUpdateRules.needsStructuralRebuild(before, List.of("u2", "u1")));
        assertTrue(ActiveNowStripUpdateRules.needsStructuralRebuild(before, List.of("u1")));
        assertTrue(ActiveNowStripUpdateRules.needsStructuralRebuild(before, List.of("u1", "u2", "u3")));
    }
}
