package com.nexachat.summary;

import java.util.Objects;

public class SummaryProviderException extends RuntimeException {

    private final SummaryFallbackReason fallbackReason;

    public SummaryProviderException(SummaryFallbackReason fallbackReason) {
        super(Objects.requireNonNull(fallbackReason, "fallbackReason must not be null").name());
        this.fallbackReason = fallbackReason;
    }

    public SummaryProviderException(SummaryFallbackReason fallbackReason, Throwable cause) {
        super(Objects.requireNonNull(fallbackReason, "fallbackReason must not be null").name(), cause);
        this.fallbackReason = fallbackReason;
    }

    public SummaryFallbackReason fallbackReason() {
        return fallbackReason;
    }
}
