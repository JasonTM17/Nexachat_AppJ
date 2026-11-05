package com.nexachat.summary;

import java.util.Objects;

/** Immutable POST body for a conversation summary request. */
public final class SummaryRequest {
    private final SummaryScope scope;
    private final Integer limit;
    private final SummaryLanguage language;
    private final boolean refresh;

    SummaryRequest() {
        this(SummaryScope.UNREAD, null, SummaryLanguage.AUTO, false);
    }

    public SummaryRequest(
            SummaryScope scope,
            Integer limit,
            SummaryLanguage language,
            boolean refresh) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.limit = limit;
        this.language = language == null ? SummaryLanguage.AUTO : language;
        this.refresh = refresh;
    }

    public SummaryScope getScope() {
        return scope;
    }

    public Integer getLimit() {
        return limit;
    }

    public SummaryLanguage getLanguage() {
        return language;
    }

    public boolean isRefresh() {
        return refresh;
    }
}
