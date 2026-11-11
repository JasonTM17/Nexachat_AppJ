package com.nexachat.firebase.projection;

import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class FirebaseProjectionRetryQueueTest {

    @Test
    void retriesWithDelayAndRetainsFailuresUntilTheProjectionSucceeds() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-10T12:00:00Z"));
        FirebaseProjectionRetryQueue queue = new FirebaseProjectionRetryQueue(
                clock, new FirebaseProjectionMetrics(new SimpleMeterRegistry()));
        AtomicInteger attempts = new AtomicInteger();
        queue.enqueue("account:test", () -> {
            if (attempts.incrementAndGet() < 2) {
                throw new IllegalStateException("retry");
            }
        });

        queue.retryDue();
        assertThat(attempts).hasValue(0);
        clock.advance(Duration.ofSeconds(2));
        queue.retryDue();
        assertThat(attempts).hasValue(1);
        assertThat(queue.pendingCount()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(10));
        queue.retryDue();

        assertThat(attempts).hasValue(2);
        assertThat(queue.pendingCount()).isZero();
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }
    }
}
