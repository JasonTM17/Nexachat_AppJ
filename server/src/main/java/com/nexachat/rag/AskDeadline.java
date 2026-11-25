package com.nexachat.rag;

import java.util.concurrent.TimeUnit;

/**
 * Nano-clock deadline for one synchronous ask request. The deadline is a
 * response-selection boundary: work that finishes too late is discarded and the
 * caller receives retryable timeout metadata instead of stale or over-budget output.
 */
final class AskDeadline {

    private static final long MIN_NETWORK_BUDGET_MS = 100;

    private final long deadlineNanos;

    private AskDeadline(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    static AskDeadline start(long budgetMs) {
        return new AskDeadline(System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(Math.max(1, budgetMs)));
    }

    long remainingMs() {
        return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
    }

    boolean expired() {
        return System.nanoTime() - deadlineNanos >= 0;
    }

    void check() {
        if (expired()) {
            throw new AskDeadlineExceededException();
        }
    }

    /** Remaining budget suitable for a nested network call. */
    long networkBudgetMs() {
        check();
        long remaining = remainingMs();
        if (remaining < MIN_NETWORK_BUDGET_MS) {
            throw new AskDeadlineExceededException();
        }
        return remaining;
    }
}

/** Internal control-flow exception; never serialized or exposed as a 500. */
final class AskDeadlineExceededException extends RuntimeException {
    AskDeadlineExceededException() {
        super("ask deadline exceeded", null, false, false);
    }
}
