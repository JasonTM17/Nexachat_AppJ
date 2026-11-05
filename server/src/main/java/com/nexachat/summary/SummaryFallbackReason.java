package com.nexachat.summary;

public enum SummaryFallbackReason {
    NO_CONSENT,
    LLM_DISABLED,
    LLM_TIMEOUT,
    LLM_BAD_OUTPUT,
    LLM_HTTP_ERROR
}
