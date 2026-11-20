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

    private static final String MINUTE_WINDOW = "MINUTE";
    private static final String DAY_WINDOW = "DAY";

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
        if (!tryConsume(userId, count)) {
            throw ApiException.of(ApiErrorCode.RATE_LIMITED, "Translate request limit exceeded");
        }
    }

    /**
     * Non-throwing variant: returns {@code false} when a limit is exhausted
     * so a batch can report the refusal per item without aborting the rest of
     * the batch. A refusal spends nothing: the minute window is refunded when
     * the daily window refuses, and the daily window is never touched when the
     * minute window refuses.
     */
    @Transactional
    public boolean tryConsume(UUID userId, int count) {
        if (count <= 0) {
            return true;
        }
        Instant now = clock.instant();
        Instant dayStart = LocalDate.ofInstant(now, ZoneOffset.UTC)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        // The short window goes first: when it refuses, nothing is spent, so a
        // burst cannot burn the daily allowance without producing a translation.
        Instant minuteStart = now.truncatedTo(ChronoUnit.MINUTES);
        if (!repository.tryConsume(userId, MINUTE_WINDOW, minuteStart,
                properties.getRateLimitPerMinute(), count)) {
            return false;
        }
        if (repository.tryConsume(userId, DAY_WINDOW, dayStart, properties.getRateLimitPerDay(), count)) {
            return true;
        }
        // The daily window refused after the minute window already consumed:
        // return the minute tokens so a refusal still spends nothing. The
        // refund runs in the same transaction as the consume above, and the
        // upsert already locked the row, so it cannot race another consumer.
        repository.refund(userId, MINUTE_WINDOW, minuteStart, count);
        return false;
    }

    /** Single-token convenience overload for the per-item batch path. */
    @Transactional
    public void consumeOne(UUID userId) {
        consume(userId, 1);
    }

    /**
     * Returns one consumed token to both windows. Called when a consume bought
     * no translation — the provider chain failed, its output was rejected, or
     * a consent revoke won the post-generation race — so a failed generation
     * never bills the caller's budget. Refunding a window that owes nothing
     * (already refunded, or the consume never happened) is a no-op: the
     * repository deletes the row when the refund would take it to zero, and a
     * missing row matches nothing.
     */
    @Transactional
    public void refundOne(UUID userId) {
        Instant now = clock.instant();
        Instant dayStart = LocalDate.ofInstant(now, ZoneOffset.UTC)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        Instant minuteStart = now.truncatedTo(ChronoUnit.MINUTES);
        repository.refund(userId, MINUTE_WINDOW, minuteStart, 1);
        repository.refund(userId, DAY_WINDOW, dayStart, 1);
    }
}
