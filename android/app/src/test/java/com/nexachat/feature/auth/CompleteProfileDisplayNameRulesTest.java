package com.nexachat.feature.auth;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class CompleteProfileDisplayNameRulesTest {

    @Test
    public void blankAfterTrimIsRequired() {
        assertEquals(
                CompleteProfileDisplayNameRules.Problem.BLANK,
                CompleteProfileDisplayNameRules.problem("   "));
    }

    @Test
    public void emptyIsRequired() {
        assertEquals(
                CompleteProfileDisplayNameRules.Problem.BLANK,
                CompleteProfileDisplayNameRules.problem(""));
    }

    @Test
    public void sixtyOneCharactersIsTooLong() {
        assertEquals(
                CompleteProfileDisplayNameRules.Problem.TOO_LONG,
                CompleteProfileDisplayNameRules.problem("a".repeat(61)));
    }

    @Test
    public void sixtyCharactersIsValid() {
        assertNull(CompleteProfileDisplayNameRules.problem("a".repeat(60)));
    }

    @Test
    public void trimBeforeLengthCheck() {
        assertNull(CompleteProfileDisplayNameRules.problem(" " + "a".repeat(60) + " "));
        assertEquals(
                CompleteProfileDisplayNameRules.Problem.TOO_LONG,
                CompleteProfileDisplayNameRules.problem(" " + "a".repeat(61) + " "));
    }

    @Test
    public void normalizeTrimsWhitespace() {
        assertEquals("Minh", CompleteProfileDisplayNameRules.normalize("  Minh  "));
    }

    @Test
    public void isValidMatchesProblem() {
        assertTrue(CompleteProfileDisplayNameRules.isValid("a".repeat(60)));
        assertTrue(!CompleteProfileDisplayNameRules.isValid(""));
    }
}
