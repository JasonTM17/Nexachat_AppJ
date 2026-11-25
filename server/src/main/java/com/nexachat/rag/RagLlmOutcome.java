package com.nexachat.rag;

/**
 * Typed result of one answer-generation attempt. A valid provider abstention is
 * deliberately distinct from provider failure and bulkhead rejection so the
 * router never mistakes "the evidence does not answer this" for a degradation.
 */
public record RagLlmOutcome(
        Status status,
        RagLlmAnswer answer,
        boolean llmAttempted,
        boolean consentRequired,
        boolean contextSuppressed,
        boolean deadlineExceeded,
        boolean sensitiveContentRedacted) {

    public enum Status {
        ANSWER,
        ABSTAIN,
        PROVIDER_ERROR,
        BULKHEAD_REJECTED
    }

    static RagLlmOutcome answer(RagLlmAnswer answer, boolean sensitiveContentRedacted) {
        return new RagLlmOutcome(Status.ANSWER, answer, true,
                false, false, false, sensitiveContentRedacted);
    }

    static RagLlmOutcome abstain() {
        return new RagLlmOutcome(Status.ABSTAIN, null, true,
                false, false, false, false);
    }

    static RagLlmOutcome providerError(boolean llmAttempted) {
        return providerError(llmAttempted, false, false, false);
    }

    static RagLlmOutcome providerError(
            boolean llmAttempted,
            boolean consentRequired,
            boolean contextSuppressed,
            boolean deadlineExceeded) {
        return new RagLlmOutcome(Status.PROVIDER_ERROR, null, llmAttempted,
                consentRequired, contextSuppressed, deadlineExceeded, false);
    }

    static RagLlmOutcome bulkheadRejected(boolean deadlineExceeded) {
        // The lane was entered, but no provider invocation occurred: keep
        // llmAttempted factual instead of counting a capacity rejection as a call.
        return new RagLlmOutcome(Status.BULKHEAD_REJECTED, null, false,
                false, false, deadlineExceeded, false);
    }
}
