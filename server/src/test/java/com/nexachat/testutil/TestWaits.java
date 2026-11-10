package com.nexachat.testutil;

import org.awaitility.Awaitility;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Bounded polling for asynchronous test conditions.
 *
 * <p>Tests must coordinate on the condition itself, never on a fixed
 * {@code Thread.sleep}: a fixed sleep is either shorter than the condition
 * needs (flaky) or longer than it needs (slow). This helper waits for the
 * caller's condition with a deadline, a short poll interval, and a failure
 * message that names what never became true.
 */
public final class TestWaits {

    /** Deadline for a condition that should be observable inside one test JVM. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /** Short interval between condition checks; not a coordination delay. */
    public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(20);

    private TestWaits() {
    }

    public static void until(String description, BooleanSupplier condition) {
        until(description, DEFAULT_TIMEOUT, condition);
    }

    public static void until(String description, Duration timeout, BooleanSupplier condition) {
        Awaitility.await(description)
                .atMost(timeout)
                .pollInterval(DEFAULT_POLL_INTERVAL)
                .until(condition::getAsBoolean);
    }
}
