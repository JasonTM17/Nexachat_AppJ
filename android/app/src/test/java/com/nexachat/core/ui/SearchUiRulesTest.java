package com.nexachat.core.ui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SearchUiRulesTest {

    @Test
    public void shouldShowLoading_onlyWhenLoadingAndNoResults() {
        assertTrue(SearchUiRules.shouldShowLoading(true, 0));
        assertFalse("Do not show full loader when results already exist", SearchUiRules.shouldShowLoading(true, 5));
        assertFalse(SearchUiRules.shouldShowLoading(false, 0));
    }

    @Test
    public void shouldShowError_onlyWhenErrorAndNoResults() {
        // D6 defect fix verification
        assertTrue("Error must show retry placeholder when no results", SearchUiRules.shouldShowError(true, 0));
        assertFalse("Do not replace results with full error view", SearchUiRules.shouldShowError(true, 3));
        assertFalse("No error -> no error view", SearchUiRules.shouldShowError(false, 0));
    }

    @Test
    public void shouldShowEmpty_neverShowsWhenErrorOccurred() {
        // D6 root cause: error was displayed as "no results"
        assertFalse("Error must NOT be shown as empty state",
                SearchUiRules.shouldShowEmpty(true, false, 0, "hello"));

        // Only genuine successful empty search shows empty
        assertTrue("Successful zero-result query shows empty state",
                SearchUiRules.shouldShowEmpty(false, false, 0, "hello"));

        // Blank query or loading or results > 0 must not show empty
        assertFalse(SearchUiRules.shouldShowEmpty(false, false, 0, ""));
        assertFalse(SearchUiRules.shouldShowEmpty(false, false, 0, "   "));
        assertFalse(SearchUiRules.shouldShowEmpty(false, false, 0, null));
        assertFalse(SearchUiRules.shouldShowEmpty(false, true, 0, "hello"));
        assertFalse(SearchUiRules.shouldShowEmpty(false, false, 2, "hello"));
        assertFalse("One character is below the server minimum, not an empty result",
                SearchUiRules.shouldShowEmpty(false, false, 0, "a"));
    }

    @Test
    public void shouldShowQueryTooShort_onlyForASingleCharacter() {
        assertTrue(SearchUiRules.shouldShowQueryTooShort("a"));
        assertTrue(SearchUiRules.shouldShowQueryTooShort(" a "));
        assertFalse(SearchUiRules.shouldShowQueryTooShort(""));
        assertFalse(SearchUiRules.shouldShowQueryTooShort("  "));
        assertFalse(SearchUiRules.shouldShowQueryTooShort(null));
        assertFalse(SearchUiRules.shouldShowQueryTooShort("ab"));
    }

    @Test
    public void shouldShowResults_whenResultCountGreaterThanZero() {
        assertTrue(SearchUiRules.shouldShowResults(1));
        assertTrue(SearchUiRules.shouldShowResults(10));
        assertFalse(SearchUiRules.shouldShowResults(0));
    }
}
