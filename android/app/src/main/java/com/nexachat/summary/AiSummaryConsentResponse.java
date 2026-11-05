package com.nexachat.summary;

/** Settings response; null consent from an older server remains disabled. */
public final class AiSummaryConsentResponse {
    private final Boolean aiSummaryConsent;

    AiSummaryConsentResponse() {
        this(null);
    }

    public AiSummaryConsentResponse(Boolean aiSummaryConsent) {
        this.aiSummaryConsent = aiSummaryConsent;
    }

    public Boolean getAiSummaryConsent() {
        return aiSummaryConsent;
    }
}
