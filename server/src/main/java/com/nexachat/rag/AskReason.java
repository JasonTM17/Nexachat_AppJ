package com.nexachat.rag;

/**
 * Machine-readable reason for an ask degradation or no-answer result. The
 * values are serialized as additive metadata; clients that do not know them can
 * continue to use {@link AskMode} exactly as before.
 */
public enum AskReason {
    RAG_DISABLED,
    NO_EVIDENCE,
    LOW_SCORE,
    SINGLE_FLIGHT_TIMEOUT,
    CONTEXT_CHANGED,
    PROVIDER_ERROR,
    BULKHEAD_REJECTED,
    CONSENT_REQUIRED,
    CONTEXT_SUPPRESSED,
    TIMEOUT,
    IN_PROGRESS
}
