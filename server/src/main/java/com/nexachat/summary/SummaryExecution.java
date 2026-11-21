package com.nexachat.summary;

import java.util.Set;
import java.util.UUID;

/** Internal authorization facts travel with the selected response, never in public JSON. */
record SummaryExecution(SummaryConsentRepository.ConsentSnapshot consentSnapshot,
                        Set<UUID> expectedExclusions, SummaryResponse response) {
    SummaryExecution {
        if ("LLM".equals(response.generatedBy())) {
            expectedExclusions = Set.copyOf(expectedExclusions);
        } else {
            consentSnapshot = null;
            expectedExclusions = Set.of();
        }
    }
}
