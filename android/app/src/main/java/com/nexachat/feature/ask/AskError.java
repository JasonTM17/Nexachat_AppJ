package com.nexachat.feature.ask;

/** Stable, presentation-safe error categories for ask requests. */
public enum AskError {
    VALIDATION,
    CHAT_UNAVAILABLE,
    RATE_LIMITED,
    CONSENT_REQUIRED,
    NETWORK,
    UNKNOWN
}
