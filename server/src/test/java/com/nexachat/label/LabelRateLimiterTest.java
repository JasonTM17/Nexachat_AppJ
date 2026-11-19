package com.nexachat.label;

import com.nexachat.auth.RateLimitService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.testutil.MutableTimeMeter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Locks the observed contract of {@link LabelRateLimiter}: the constructor
 * rejects a non-positive write budget, {@code checkWrite} spends a per-user
 * one-minute token bucket, and a denied spend surfaces {@code RATE_LIMITED}
 * carrying the delay reported by the consumption result.
 *
 * <p>Budgets below the production default are injected so the cases stay fast.
 * The bucket clock is frozen through {@link MutableTimeMeter} because buckets
 * refill greedily — "the N+1 write is denied" only holds while the clock stands
 * still.
 */
class LabelRateLimiterTest {

    private static final long WRITES_PER_MINUTE = 3;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    @Test
    void rejectsAWriteBudgetBelowOne() {
        assertThatThrownBy(() -> new LabelRateLimiter(new RateLimitService(), 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("nexachat.rate-limit.labels.writes-per-minute must be at least 1");

        assertThat(new LabelRateLimiter(new RateLimitService(), 1)).isNotNull();
    }

    @Test
    void consumesThePerUserLabelWriteBudgetInsideOneMinute() {
        LabelRateLimiter limiter = limiterWithFrozenClock(new MutableTimeMeter());
        UUID user = UUID.randomUUID();

        for (long write = 1; write <= WRITES_PER_MINUTE; write++) {
            long expectedWrite = write;
            assertThatCode(() -> limiter.checkWrite(user))
                    .as("write %d is inside the %d/minute budget", expectedWrite, WRITES_PER_MINUTE)
                    .doesNotThrowAnyException();
        }

        assertThatThrownBy(() -> limiter.checkWrite(user))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(ApiErrorCode.RATE_LIMITED));
    }

    @Test
    void anotherUserKeepsItsOwnBudget() {
        LabelRateLimiter limiter = limiterWithFrozenClock(new MutableTimeMeter());
        UUID exhausted = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();

        for (long write = 1; write <= WRITES_PER_MINUTE; write++) {
            limiter.checkWrite(exhausted);
        }
        assertThatThrownBy(() -> limiter.checkWrite(exhausted)).isInstanceOf(ApiException.class);

        assertThatCode(() -> limiter.checkWrite(fresh)).doesNotThrowAnyException();
    }

    @Test
    void deniedWritesRaiseRateLimitedWithTheRetryDelayFromConsumption() {
        RateLimitService rateLimitService = mock(RateLimitService.class);
        when(rateLimitService.tryConsume(anyString(), anyLong(), any(Duration.class)))
                .thenReturn(new RateLimitService.Consumption(false, 42));
        LabelRateLimiter limiter = new LabelRateLimiter(rateLimitService, WRITES_PER_MINUTE);
        UUID user = UUID.randomUUID();

        assertThatThrownBy(() -> limiter.checkWrite(user))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ApiErrorCode.RATE_LIMITED);
                    assertThat(exception.getCode().getCode()).isEqualTo("RATE_LIMITED");
                    assertThat(exception.getHttpStatus()).isEqualTo(429);
                    assertThat(exception.getMessage()).isEqualTo("Rate limit exceeded, try again later");
                    assertThat(exception.getDetails()).containsExactly(Map.entry("retryAfterSeconds", 42L));
                });

        // The whole label mutation budget is one per-user bucket over a one-minute window.
        verify(rateLimitService).tryConsume("label-write|user:" + user, WRITES_PER_MINUTE, WINDOW);
    }

    /** Standalone service (tests, manual wiring) on an empty bucket cache and a clock the test owns. */
    private static LabelRateLimiter limiterWithFrozenClock(MutableTimeMeter meter) {
        RateLimitService rateLimitService = new RateLimitService(meter);
        rateLimitService.reset();
        return new LabelRateLimiter(rateLimitService, WRITES_PER_MINUTE);
    }
}
