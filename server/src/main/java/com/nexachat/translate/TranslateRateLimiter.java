package com.nexachat.translate;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Per-user translate budget. Tokens are consumed only on a cache miss —
 * replayed translations are cheap reads and never rate-limit the member. A
 * batch consumes one token per message actually sent to a provider.
 */
@Service
@Profile("!test")
public class TranslateRateLimiter {

    private final TranslateRequestLimitRepository repository;
    private final TranslateProperties properties;
    private final Clock clock;

    public TranslateRateLimiter(TranslateRequestLimitRepository repository,
                                TranslateProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Consumes {@code count} generation tokens, rolled back atomically if
     * either window is exhausted (the exception rolls back the day increment
     * as well, matching the summary limiter).
     */
    @Transactional
    public void consume(UUID userId, int count) {
        if (count <= 0) {
            return;
        }
        Instant now = clock.instant();
        Instant dayStart = LocalDate.ofInstant(now, ZoneOffset.UTC)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        if (!repository.tryConsume(userId, "DAY", dayStart, properties.getRateLimitPerDay(), count)) {
            throw ApiException.of(ApiErrorCode.RATE_LIMITED, "Translate request limit exceeded");
        }

        Instant minuteStart = now.truncatedTo(ChronoUnit.MINUTES);
        if (!repository.tryConsume(userId, "MINUTE", minuteStart,
                properties.getRateLimitPerMinute(), count)) {
            throw ApiException.of(ApiErrorCode.RATE_LIMITED, "Translate request limit exceeded");
        }
    }

    /** Single-token convenience overload for the per-item batch path. */
    @Transactional
    public void consumeOne(UUID userId) {
        consume(userId, 1);
    }
}
