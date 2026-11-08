package com.nexachat.summary;

import java.util.Objects;

public class SummaryProviderException extends RuntimeException {

    private final SummaryFallbackReason fallbackReason;
    private final boolean retryable;

    public SummaryProviderException(SummaryFallbackReason fallbackReason) {
        this(fallbackReason, false, null);
    }

    public SummaryProviderException(SummaryFallbackReason fallbackReason, Throwable cause) {
        this(fallbackReason, false, cause);
    }

    public SummaryProviderException(SummaryFallbackReason fallbackReason, boolean retryable) {
        this(fallbackReason, retryable, null);
    }

    public SummaryProviderException(SummaryFallbackReason fallbackReason, boolean retryable, Throwable cause) {
        super(Objects.requireNonNull(fallbackReason, "fallbackReason must not be null").name(), cause);
        this.fallbackReason = fallbackReason;
        this.retryable = retryable;
    }

    public SummaryFallbackReason fallbackReason() {
        return fallbackReason;
    }

    /**
     * Whether another provider may be tried for this failure. Transport
     * errors, timeouts, HTTP 5xx and HTTP 429 are retryable; a bad request,
     * an auth failure, a disabled provider and malformed model output are not.
     */
    public boolean isRetryable() {
        return retryable;
    }
}
