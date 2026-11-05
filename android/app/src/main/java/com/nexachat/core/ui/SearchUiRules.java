package com.nexachat.core.ui;

/**
 * Pure Java presentation rules for in-chat search display states (D6).
 */
public final class SearchUiRules {

    private SearchUiRules() {}

    /**
     * Determines whether search is in initial loading state (first page loading, no results yet).
     */
    public static boolean shouldShowLoading(boolean loading, int resultCount) {
        return loading && resultCount == 0;
    }

    /**
     * Determines whether search error state should be rendered (with retry affordance).
     * Error is shown when hasError is true and no results are present.
     */
    public static boolean shouldShowError(boolean hasError, int resultCount) {
        return hasError && resultCount == 0;
    }

    /**
     * Determines whether the empty state ("No messages found") should be rendered.
     * Must ONLY show when there is NO error, search is NOT loading, results are empty,
     * and query is non-blank.
     */
    public static boolean shouldShowEmpty(boolean hasError, boolean loading, int resultCount, String query) {
        if (hasError || loading || resultCount > 0) {
            return false;
        }
        return query != null && !query.trim().isEmpty();
    }

    /**
     * Determines whether the results list should be rendered.
     */
    public static boolean shouldShowResults(int resultCount) {
        return resultCount > 0;
    }
}
