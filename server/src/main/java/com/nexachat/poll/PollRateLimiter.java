package com.nexachat.poll;

import com.nexachat.auth.RateLimitService;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Per-user mutation budgets for polls, built on the shared in-memory
 * {@link RateLimitService} used by the other write endpoints. The buckets are
 * split so a user polling a busy group cannot burn the create/close budget
 * with votes.
 */
@Component
public class PollRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final RateLimitService rateLimitService;
    private final long createPerMinute;
    private final long votesPerMinute;
    private final long managePerMinute;

    public PollRateLimiter(
            RateLimitService rateLimitService,
            @Value("${nexachat.rate-limit.polls.create-per-minute:10}") long createPerMinute,
            @Value("${nexachat.rate-limit.polls.votes-per-minute:120}") long votesPerMinute,
            @Value("${nexachat.rate-limit.polls.manage-per-minute:30}") long managePerMinute) {
        this.rateLimitService = rateLimitService;
        this.createPerMinute = requirePositive(createPerMinute, "create-per-minute");
        this.votesPerMinute = requirePositive(votesPerMinute, "votes-per-minute");
        this.managePerMinute = requirePositive(managePerMinute, "manage-per-minute");
    }

    void checkCreate(UUID userId) {
        check("poll-create|user:" + userId, createPerMinute);
    }

    void checkVote(UUID userId) {
        check("poll-vote|user:" + userId, votesPerMinute);
    }

    void checkManage(UUID userId) {
        check("poll-manage|user:" + userId, managePerMinute);
    }

    private void check(String bucket, long capacity) {
        RateLimitService.Consumption consumption =
                rateLimitService.tryConsume(bucket, capacity, WINDOW);
        if (!consumption.allowed()) {
            throw ApiException.of(ApiErrorCode.RATE_LIMITED,
                    "Rate limit exceeded, try again later",
                    Map.of("retryAfterSeconds", consumption.retryAfterSeconds()));
        }
    }

    private static long requirePositive(long value, String name) {
        if (value < 1) {
            throw new IllegalStateException(
                    "nexachat.rate-limit.polls." + name + " must be at least 1");
        }
        return value;
    }
}
