package com.nexachat.rag;

import com.nexachat.summary.SummaryConsentRepository;
import java.util.UUID;

/** Internal response and consent snapshot kept out of the public JSON shape. */
record AskExecution(SummaryConsentRepository.ConsentSnapshot consentSnapshot, AskResponse response,
                    UUID conversationId, RagQuestionCache.ContextSnapshot contextSnapshot) {
    AskExecution(SummaryConsentRepository.ConsentSnapshot consentSnapshot, AskResponse response) {
        this(consentSnapshot, response, null, null);
    }
}
