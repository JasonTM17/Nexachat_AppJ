package com.nexachat.feature.chat;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class VoiceStartCompletionRulesTest {

    @Test
    public void completionFromTheCurrentGenerationApplies() {
        assertFalse(VoiceStartCompletionRules.isStale(3L, 3L));
    }

    @Test
    public void completionFromAnOlderGenerationIsIgnored() {
        assertTrue(VoiceStartCompletionRules.isStale(2L, 3L));
    }

    @Test
    public void completionSubmittedBeforeTeardownIsIgnored() {
        // onDestroyView bumps the generation, so a completion from the
        // pre-destroy attempt must not touch the next view's flags.
        assertTrue(VoiceStartCompletionRules.isStale(4L, 5L));
    }
}
