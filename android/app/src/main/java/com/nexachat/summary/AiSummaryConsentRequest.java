package com.nexachat.summary;

/** Partial settings update body for the user's summary consent. */
public final class AiSummaryConsentRequest {
    private final boolean aiSummaryConsent;

    AiSummaryConsentRequest() {
        this(false);
    }

    public AiSummaryConsentRequest(boolean aiSummaryConsent) {
        this.aiSummaryConsent = aiSummaryConsent;
    }

    public boolean isAiSummaryConsent() {
        return aiSummaryConsent;
    }
}
