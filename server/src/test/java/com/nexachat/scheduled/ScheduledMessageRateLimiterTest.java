package com.nexachat.scheduled;

import com.nexachat.auth.RateLimitService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Per-sender dispatch throttle: buckets are independent and bounded. */
class ScheduledMessageRateLimiterTest {

    @Test
    void deniesAfterThePerSenderCapacityAndReportsRetryAfter() {
        ScheduledMessageRateLimiter limiter =
                new ScheduledMessageRateLimiter(new RateLimitService(), 2);
        UUID sender = UUID.randomUUID();

        assertThat(limiter.tryConsume(sender).allowed()).isTrue();
        assertThat(limiter.tryConsume(sender).allowed()).isTrue();

        RateLimitService.Consumption denied = limiter.tryConsume(sender);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isPositive();
    }

    @Test
    void bucketsAreIndependentPerSender() {
        ScheduledMessageRateLimiter limiter =
                new ScheduledMessageRateLimiter(new RateLimitService(), 1);

        assertThat(limiter.tryConsume(UUID.randomUUID()).allowed()).isTrue();
        assertThat(limiter.tryConsume(UUID.randomUUID()).allowed()).isTrue();
    }
}
