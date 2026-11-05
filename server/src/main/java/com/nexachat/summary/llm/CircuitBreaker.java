package com.nexachat.summary.llm;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Component
public final class CircuitBreaker {

    private static final int FAILURE_THRESHOLD = 5;
    private static final Duration OPEN_DURATION = Duration.ofSeconds(60);

    private final Clock clock;
    private int consecutiveFailures;
    private Instant openUntil;

    public CircuitBreaker(Clock clock) {
        this.clock = clock;
    }

    public synchronized boolean allowRequest() {
        if (openUntil == null) {
            return true;
        }
        if (clock.instant().isBefore(openUntil)) {
            return false;
        }
        openUntil = null;
        consecutiveFailures = 0;
        return true;
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        openUntil = null;
    }

    public synchronized void recordFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= FAILURE_THRESHOLD) {
            consecutiveFailures = FAILURE_THRESHOLD;
            openUntil = clock.instant().plus(OPEN_DURATION);
        }
    }

    synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }
}
