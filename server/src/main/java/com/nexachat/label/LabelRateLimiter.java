package com.nexachat.label;

import com.nexachat.auth.RateLimitService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/** Shared per-user budget for all label mutations. */
@Component
public class LabelRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final RateLimitService rateLimitService;
    private final long writesPerMinute;

    public LabelRateLimiter(
            RateLimitService rateLimitService,
            @Value("${nexachat.rate-limit.labels.writes-per-minute:30}") long writesPerMinute) {
        this.rateLimitService = rateLimitService;
        if (writesPerMinute < 1) {
            throw new IllegalStateException(
                    "nexachat.rate-limit.labels.writes-per-minute must be at least 1");
        }
        this.writesPerMinute = writesPerMinute;
    }

    public void checkWrite(UUID userId) {
        RateLimitService.Consumption consumption = rateLimitService.tryConsume(
                "label-write|user:" + userId, writesPerMinute, WINDOW);
        if (!consumption.allowed()) {
            throw ApiException.of(ApiErrorCode.RATE_LIMITED,
                    "Rate limit exceeded, try again later",
                    Map.of("retryAfterSeconds", consumption.retryAfterSeconds()));
        }
    }
}
