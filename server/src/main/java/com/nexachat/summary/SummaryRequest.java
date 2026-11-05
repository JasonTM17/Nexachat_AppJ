package com.nexachat.summary;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public record SummaryRequest(
        @NotNull SummaryScope scope,
        Integer limit,
        SummaryLanguage language,
        Boolean refresh) {

    public SummaryRequest {
        language = language == null ? SummaryLanguage.AUTO : language;
        refresh = refresh == null ? Boolean.FALSE : refresh;
    }

    @AssertTrue(message = "limit must be between 5 and 500 for LAST_N")
    public boolean isLastNLimitValid() {
        return scope != SummaryScope.LAST_N || (limit != null && limit >= 5 && limit <= 500);
    }
}
