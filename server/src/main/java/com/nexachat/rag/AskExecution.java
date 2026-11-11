package com.nexachat.rag;

import com.nexachat.summary.SummaryConsentRepository;

/** Internal response and consent snapshot kept out of the public JSON shape. */
record AskExecution(SummaryConsentRepository.ConsentSnapshot consentSnapshot, AskResponse response) {
}
