package com.nexachat.rag;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code AskRateLimiter} keys its counters by the UTC minute, so the HTTP test
 * that expects the 21st request to be denied is only valid inside one window.
 * These tests pin the clock and prove both halves: 20 requests are admitted and
 * the 21st is rejected, while crossing the minute boundary starts a fresh
 * window (the exact condition that made {@code AskApiIT} intermittently see 200).
 */
class AskRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-11T10:30:15Z"));
    private final Map<String, Integer> counters = new HashMap<>();
    private AskRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        RagRequestLimitRepository repository = mock(RagRequestLimitRepository.class);
        when(repository.tryConsume(any(UUID.class), anyString(), any(Instant.class), anyInt()))
                .thenAnswer(invocation -> {
                    UUID userId = invocation.getArgument(0);
                    String windowType = invocation.getArgument(1);
                    Instant windowStart = invocation.getArgument(2);
                    int limit = invocation.getArgument(3);
                    String key = userId + "|" + windowType + "|" + windowStart;
                    int consumed = counters.merge(key, 1, Integer::sum);
                    return consumed <= limit;
                });
        rateLimiter = new AskRateLimiter(repository, new RagProperties(), clock);
    }

    @Test
    void withinOneMinuteTheTwentyFirstRequestIsRateLimited() {
        UUID user = UUID.randomUUID();

        for (int attempt = 1; attempt <= 20; attempt++) {
            int expectedAttempt = attempt;
            assertThatCode(() -> rateLimiter.consume(user))
                    .as("request %d is inside the 20/minute budget", expectedAttempt)
                    .doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> rateLimiter.consume(user))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(ApiErrorCode.RATE_LIMITED));
    }

    @Test
    void crossingTheMinuteBoundaryStartsAFreshWindow() {
        UUID user = UUID.randomUUID();
        clock.set(Instant.parse("2026-11-11T10:30:59.900Z"));
        for (int attempt = 1; attempt <= 20; attempt++) {
            rateLimiter.consume(user);
        }

        clock.set(Instant.parse("2026-11-11T10:31:00Z"));

        // Same user, next minute: a new window and therefore a new budget. This is
        // why the 21st HTTP request in AskApiIT must pin the clock.
        assertThatCode(() -> rateLimiter.consume(user)).doesNotThrowAnyException();
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void set(Instant next) {
            this.instant = next;
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
            return instant;
        }
    }
}
