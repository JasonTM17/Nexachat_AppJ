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
        this(scope, limit, language, refresh, timeZone, false);
    }

    /**
     * A request that sends no zone, so the server applies its own default day. Used once when
     * the device zone was rejected.
     */
    public static SummaryRequest withoutTimeZone(
            SummaryScope scope,
            Integer limit,
            SummaryLanguage language,
            boolean refresh) {
        return new SummaryRequest(scope, limit, language, refresh, null, true);
    }

    private SummaryRequest(
            SummaryScope scope,
            Integer limit,
            SummaryLanguage language,
            boolean refresh,
            String timeZone,
            boolean omitTimeZone) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.limit = limit;
        this.language = language == null ? SummaryLanguage.AUTO : language;
        this.refresh = refresh;
        this.timeZone = omitTimeZone ? null : resolveTimeZone(timeZone);
    }

    private static String resolveTimeZone(String timeZone) {
        return timeZone == null || timeZone.trim().isEmpty()
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

    /**
     * IANA zone id of the device, or null when the request omits it; the server reads it only
     * for the TODAY scope.
     */
    public String getTimeZone() {
        return timeZone;
    }
}
