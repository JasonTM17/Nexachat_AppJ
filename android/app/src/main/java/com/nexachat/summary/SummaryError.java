package com.nexachat.summary;

/** Stable client-side error categories for summary and consent requests. */
public enum SummaryError {
    TOO_FEW,
    RATE_LIMITED,
    NOT_MEMBER,
    E2EE_UNAVAILABLE,
    INVALID_TIME_ZONE,
    NETWORK,
    UNKNOWN
}
