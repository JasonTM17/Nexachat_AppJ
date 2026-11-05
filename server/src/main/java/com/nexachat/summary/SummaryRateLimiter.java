package com.nexachat.summary;

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

@Service
@Profile("!test")
public class SummaryRateLimiter {

    private static final int PER_MINUTE = 10;
    private static final int PER_DAY = 60;

    private final SummaryRequestLimitRepository repository;
    private final Clock clock;

    public SummaryRateLimiter(SummaryRequestLimitRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public void consume(UUID userId) {
        Instant now = clock.instant();
        Instant dayStart = LocalDate.ofInstant(now, ZoneOffset.UTC)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        if (!repository.tryConsume(userId, "DAY", dayStart, PER_DAY)) {
            throw ApiException.of(ApiErrorCode.RATE_LIMITED, "Summary request limit exceeded");
        }

        Instant minuteStart = now.truncatedTo(ChronoUnit.MINUTES);
        if (!repository.tryConsume(userId, "MINUTE", minuteStart, PER_MINUTE)) {
            // This exception rolls back the daily increment as well as the minute attempt.
            throw ApiException.of(ApiErrorCode.RATE_LIMITED, "Summary request limit exceeded");
        }
    }
}
