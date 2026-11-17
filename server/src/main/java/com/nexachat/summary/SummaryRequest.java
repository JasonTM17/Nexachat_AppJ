package com.nexachat.summary;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/**
 * Summary request. {@code limit} is the N of {@code LAST_N}; {@code timeZone} (IANA id or
 * offset, absent = Asia/Ho_Chi_Minh) only decides which local day {@code TODAY} means.
 */
public record SummaryRequest(
        @NotNull SummaryScope scope,
        Integer limit,
        SummaryLanguage language,
        Boolean refresh,
        String timeZone) {

    public SummaryRequest {
        language = language == null ? SummaryLanguage.AUTO : language;
        refresh = refresh == null ? Boolean.FALSE : refresh;
    }

    public SummaryRequest(SummaryScope scope, Integer limit, SummaryLanguage language, Boolean refresh) {
        this(scope, limit, language, refresh, null);
    }

    @AssertTrue(message = "limit must be between 5 and 500 for LAST_N")
    public boolean isLastNLimitValid() {
        return scope != SummaryScope.LAST_N || (limit != null && limit >= 5 && limit <= 500);
    }
}
