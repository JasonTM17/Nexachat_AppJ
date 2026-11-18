package com.nexachat.feed;

import com.nexachat.auth.RateLimitService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/** Shared feed rate-limit checks so posts, comments, reactions and uploads use one mechanism. */
@Component
class FeedRateLimits {

    private final RateLimitService rateLimitService;

    FeedRateLimits(RateLimitService rateLimitService) {
        this.rateLimitService = rateLimitService;
    }

    void consume(String bucket, long capacity, Duration period, String message) {
        RateLimitService.Consumption consumption = rateLimitService.tryConsume(
                bucket, capacity, period);
        if (!consumption.allowed()) {
            throw ApiException.of(ApiErrorCode.RATE_LIMITED, message,
                    Map.of("retryAfterSeconds", consumption.retryAfterSeconds()));
        }
    }
}
