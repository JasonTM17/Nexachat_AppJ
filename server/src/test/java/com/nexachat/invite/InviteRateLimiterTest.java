package com.nexachat.invite;

import com.nexachat.auth.RateLimitService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InviteRateLimiterTest {

    private InviteRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        RateLimitService rateLimitService = new RateLimitService();
        // 3 resolves/min, 2 joins/min for test predictability
        rateLimiter = new InviteRateLimiter(rateLimitService, 3, 2);
    }

    @Test
    void rateLimitsResolvePerUser() {
        UUID userId = UUID.randomUUID();
        String ip = "192.168.1.10";

        rateLimiter.checkResolve(userId, ip);
        rateLimiter.checkResolve(userId, ip);
        rateLimiter.checkResolve(userId, ip);

        assertThatThrownBy(() -> rateLimiter.checkResolve(userId, "10.0.0.1"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getCode()).isEqualTo(ApiErrorCode.RATE_LIMITED);
                    assertThat(apiEx.getDetails()).containsKey("retryAfterSeconds");
                });
    }

    @Test
    void rateLimitsResolvePerIp() {
        String ip = "192.168.1.50";

        rateLimiter.checkResolve(UUID.randomUUID(), ip);
        rateLimiter.checkResolve(UUID.randomUUID(), ip);
        rateLimiter.checkResolve(UUID.randomUUID(), ip);

        assertThatThrownBy(() -> rateLimiter.checkResolve(UUID.randomUUID(), ip))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getCode()).isEqualTo(ApiErrorCode.RATE_LIMITED);
                });
    }

    @Test
    void rateLimitsJoinPerUser() {
        UUID userId = UUID.randomUUID();
        String ip = "10.0.0.2";

        rateLimiter.checkJoin(userId, ip);
        rateLimiter.checkJoin(userId, ip);

        assertThatThrownBy(() -> rateLimiter.checkJoin(userId, "10.0.0.3"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getCode()).isEqualTo(ApiErrorCode.RATE_LIMITED);
                });
    }

    @Test
    void rateLimitsJoinPerIp() {
        String ip = "10.0.0.99";

        rateLimiter.checkJoin(UUID.randomUUID(), ip);
        rateLimiter.checkJoin(UUID.randomUUID(), ip);

        assertThatThrownBy(() -> rateLimiter.checkJoin(UUID.randomUUID(), ip))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.getCode()).isEqualTo(ApiErrorCode.RATE_LIMITED);
                });
    }

    @Test
    void distinctUsersHaveIndependentBudgets() {
        UUID user1 = UUID.randomUUID();
        UUID user2 = UUID.randomUUID();

        rateLimiter.checkJoin(user1, "127.0.0.1");
        rateLimiter.checkJoin(user1, "127.0.0.1");

        // user1 is exhausted on IP 127.0.0.1
        assertThatThrownBy(() -> rateLimiter.checkJoin(user1, "127.0.0.1"))
                .isInstanceOf(ApiException.class);

        // user2 on different IP still has budget
        rateLimiter.checkJoin(user2, "127.0.0.2");
        rateLimiter.checkJoin(user2, "127.0.0.2");
    }
}
