package com.nexachat.poll;

import com.nexachat.auth.RateLimitService;
import com.nexachat.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The poll mutation buckets are per user and independent: an exhausted vote
 * bucket must not deny poll creation, and a denied call must carry the same
 * {@code RATE_LIMITED} envelope as the other mutation endpoints.
 */
class PollRateLimiterTest {

    @Test
    void deniesAfterThePerUserCapacityAndReportsRetryAfter() {
        PollRateLimiter limiter = new PollRateLimiter(new RateLimitService(), 5, 2, 5);
        UUID user = UUID.randomUUID();

        limiter.checkVote(user);
        limiter.checkVote(user);

        assertThatThrownBy(() -> limiter.checkVote(user))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getCode().getCode()).isEqualTo("RATE_LIMITED");
                    assertThat(ex.getHttpStatus()).isEqualTo(429);
                    assertThat(ex.getDetails()).containsKey("retryAfterSeconds");
                });
    }

    @Test
    void bucketsAreIndependentPerOperationAndPerUser() {
        PollRateLimiter limiter = new PollRateLimiter(new RateLimitService(), 1, 1, 1);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        limiter.checkVote(first);
        limiter.checkCreate(first);
        limiter.checkManage(first);
        limiter.checkVote(second);
    }
}
