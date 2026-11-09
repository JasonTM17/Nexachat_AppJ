package com.nexachat.invite;

import com.nexachat.auth.RateLimitService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Token-bucket rate limiter for invite link resolution and join actions,
 * applied per authenticated user and per client IP (to defend against token guessing).
 */
@Component
public class InviteRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final RateLimitService rateLimitService;
    private final long resolvePerMinute;
    private final long joinPerMinute;

    public InviteRateLimiter(
            RateLimitService rateLimitService,
            @Value("${nexachat.rate-limit.invite.resolve-per-minute:60}") long resolvePerMinute,
            @Value("${nexachat.rate-limit.invite.join-per-minute:20}") long joinPerMinute) {
        this.rateLimitService = rateLimitService;
        this.resolvePerMinute = Math.max(1, resolvePerMinute);
        this.joinPerMinute = Math.max(1, joinPerMinute);
    }

    public void checkResolve(UUID userId, String clientIp) {
        if (userId != null) {
            check("invite-resolve|user:" + userId, resolvePerMinute);
        }
        if (clientIp != null && !clientIp.isBlank()) {
            check("invite-resolve|ip:" + clientIp.trim(), resolvePerMinute);
        }
    }

    public void checkJoin(UUID userId, String clientIp) {
        if (userId != null) {
            check("invite-join|user:" + userId, joinPerMinute);
        }
        if (clientIp != null && !clientIp.isBlank()) {
            check("invite-join|ip:" + clientIp.trim(), joinPerMinute);
        }
    }

    private void check(String bucketKey, long capacity) {
        RateLimitService.Consumption consumption =
                rateLimitService.tryConsume(bucketKey, capacity, WINDOW);
        if (!consumption.allowed()) {
            throw ApiException.of(
                    ApiErrorCode.RATE_LIMITED,
                    "Rate limit exceeded, try again later",
                    Map.of("retryAfterSeconds", consumption.retryAfterSeconds()));
        }
    }
}
