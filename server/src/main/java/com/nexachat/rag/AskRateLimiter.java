package com.nexachat.rag;

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

/** Separate ask bucket: 20 requests/minute and 200/day per user by default. */
@Service
@Profile("!test")
public class AskRateLimiter {

    private final RagRequestLimitRepository repository;
    private final RagProperties properties;
    private final Clock clock;

    public AskRateLimiter(RagRequestLimitRepository repository, RagProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public void consume(UUID userId) {
        Instant now = clock.instant();
        Instant dayStart = LocalDate.ofInstant(now, ZoneOffset.UTC)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        if (!repository.tryConsume(userId, "DAY", dayStart, properties.getAsk().getRateLimitPerDay())) {
            throw ApiException.of(ApiErrorCode.RATE_LIMITED, "Ask request limit exceeded");
        }

        Instant minuteStart = now.truncatedTo(ChronoUnit.MINUTES);
        if (!repository.tryConsume(userId, "MINUTE", minuteStart, properties.getAsk().getRateLimitPerMinute())) {
            throw ApiException.of(ApiErrorCode.RATE_LIMITED, "Ask request limit exceeded");
        }
    }
}
