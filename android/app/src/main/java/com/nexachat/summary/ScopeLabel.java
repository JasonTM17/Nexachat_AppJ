package com.nexachat.summary;

/** Maps a scope to its resource key so the UI can resolve localized strings. */
public final class ScopeLabel {
    private ScopeLabel() { }

    public static String resourceName(SummaryScope scope) {
        if (scope == null) {
            throw new IllegalArgumentException("scope must not be null");
        }
        switch (scope) {
            case UNREAD:
                return "summary_scope_unread";
            case LAST_N:
                return "summary_scope_last_n";
            case TODAY:
                return "time_today";
            default:
                throw new IllegalArgumentException("Unsupported scope: " + scope);
        }
    }
}
