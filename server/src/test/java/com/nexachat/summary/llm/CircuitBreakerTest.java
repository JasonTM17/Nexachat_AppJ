package com.nexachat.summary.llm;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {

    @Test
    void opensAfterFiveFailuresAndClosesAfterSixtySeconds() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-05T12:00:00Z"));
        CircuitBreaker breaker = new CircuitBreaker(clock);

        for (int index = 0; index < 5; index++) {
            assertThat(breaker.allowRequest()).isTrue();
            breaker.recordFailure();
        }

        assertThat(breaker.allowRequest()).isFalse();
        clock.advance(Duration.ofSeconds(59));
        assertThat(breaker.allowRequest()).isFalse();
        clock.advance(Duration.ofSeconds(1));
        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.consecutiveFailures()).isZero();
    }

    @Test
    void successResetsTheConsecutiveFailureCount() {
        CircuitBreaker breaker = new CircuitBreaker(Clock.systemUTC());
        breaker.recordFailure();
        breaker.recordFailure();

        breaker.recordSuccess();

        assertThat(breaker.consecutiveFailures()).isZero();
        assertThat(breaker.allowRequest()).isTrue();
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant initial) {
            instant = new AtomicReference<>(initial);
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
            return instant.get();
        }

        private void advance(Duration duration) {
            instant.updateAndGet(value -> value.plus(duration));
        }
    }
}
