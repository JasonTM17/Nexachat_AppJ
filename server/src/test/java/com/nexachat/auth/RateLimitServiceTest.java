package com.nexachat.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitServiceTest {

    private final RateLimitService rateLimitService = new RateLimitService();

    @Test
    void tryConsume_allowsUpToCapacity_thenRejectsWithRetryAfter() {
        String key = "login|10.0.0.1";
        Duration window = Duration.ofMinutes(5);

        for (int i = 0; i < 10; i++) {
            assertThat(rateLimitService.tryConsume(key, 10, window).allowed())
                    .as("attempt %d within capacity", i + 1)
                    .isTrue();
        }

        RateLimitService.Consumption denied = rateLimitService.tryConsume(key, 10, window);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterSeconds()).isPositive();
    }

    @Test
    void tryConsume_tracksKeysIndependently() {
        Duration window = Duration.ofMinutes(5);
        String loginIp1 = "login|10.0.0.1";
        String loginIp2 = "login|10.0.0.2";
        String refreshIp1 = "refresh|10.0.0.1";

        for (int i = 0; i < 10; i++) {
            rateLimitService.tryConsume(loginIp1, 10, window);
        }
        assertThat(rateLimitService.tryConsume(loginIp1, 10, window).allowed()).isFalse();

        // A different IP and a different rule on the same IP are unaffected.
        assertThat(rateLimitService.tryConsume(loginIp2, 10, window).allowed()).isTrue();
        assertThat(rateLimitService.tryConsume(refreshIp1, 30, window).allowed()).isTrue();
        assertThat(rateLimitService.bucketCount()).isEqualTo(3);
    }
}
