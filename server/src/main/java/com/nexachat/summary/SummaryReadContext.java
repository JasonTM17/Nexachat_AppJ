package com.nexachat.summary;

/**
 * Window plus the access facts it was computed under: AI consent, the consent epoch
 * and, for {@code TODAY}, the resolved local day.
 */
public record SummaryReadContext(
        SummaryWindow window,
        boolean aiSummaryConsent,
        long consentEpoch,
        SummaryPeriod period) {
}
