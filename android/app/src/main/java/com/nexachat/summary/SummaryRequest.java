package com.nexachat.summary;

import java.util.Objects;
import java.util.TimeZone;

/** Immutable POST body for a conversation summary request. */
public final class SummaryRequest {
    private final SummaryScope scope;
    private final Integer limit;
    private final SummaryLanguage language;
    private final boolean refresh;
    private final String timeZone;

    SummaryRequest() {
        this(SummaryScope.UNREAD, null, SummaryLanguage.AUTO, false);
    }

    public SummaryRequest(
            SummaryScope scope,
            Integer limit,
            SummaryLanguage language,
            boolean refresh) {
        this(scope, limit, language, refresh, TimeZone.getDefault().getID());
    }

    public SummaryRequest(
            SummaryScope scope,
            Integer limit,
            SummaryLanguage language,
            boolean refresh,
            String timeZone) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.limit = limit;
        this.language = language == null ? SummaryLanguage.AUTO : language;
        this.refresh = refresh;
        this.timeZone = timeZone == null || timeZone.trim().isEmpty()
                ? TimeZone.getDefault().getID()
                : timeZone;
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

    /** IANA zone id of the device; the server reads it only for the TODAY scope. */
    public String getTimeZone() {
        return timeZone;
    }
}
