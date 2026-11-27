package com.nexachat.rag;

import com.nexachat.summary.SummaryConsentRepository;
import java.util.UUID;

/** Internal response and consent snapshot kept out of the public JSON shape. */
record AskExecution(SummaryConsentRepository.ConsentSnapshot consentSnapshot, AskResponse response,
                    UUID conversationId, RagQuestionCache.ContextSnapshot contextSnapshot,
                    AskSpeakerLookup.Binding speaker) {
    AskExecution(SummaryConsentRepository.ConsentSnapshot consentSnapshot, AskResponse response,
                 UUID conversationId, RagQuestionCache.ContextSnapshot contextSnapshot) {
        this(consentSnapshot, response, conversationId, contextSnapshot, null);
    }

    AskExecution(SummaryConsentRepository.ConsentSnapshot consentSnapshot, AskResponse response) {
        this(consentSnapshot, response, null, null);
    }
}
