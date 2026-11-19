package com.nexachat.translate;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TranslateRateLimiterTest {

    private final TranslateProperties properties = new TranslateProperties();

    @Test
    void usesUtcMinuteAndDayWindows() {
        TranslateRequestLimitRepository repository = mock(TranslateRequestLimitRepository.class);
        Instant now = Instant.parse("2026-11-16T23:59:45Z");
        UUID userId = UUID.randomUUID();
        when(repository.tryConsume(eq(userId), eq("DAY"), any(), anyInt(), eq(1))).thenReturn(true);
        when(repository.tryConsume(eq(userId), eq("MINUTE"), any(), anyInt(), eq(1))).thenReturn(true);

        new TranslateRateLimiter(repository, properties, Clock.fixed(now, ZoneOffset.UTC))
                .consumeOne(userId);

        verify(repository).tryConsume(userId, "DAY",
                Instant.parse("2026-11-16T00:00:00Z"), properties.getRateLimitPerDay(), 1);
        verify(repository).tryConsume(userId, "MINUTE",
                Instant.parse("2026-11-16T23:59:00Z"), properties.getRateLimitPerMinute(), 1);
    }

    @Test
    void rejectsWhenMinuteLimitIsReached() {
        TranslateRequestLimitRepository repository = mock(TranslateRequestLimitRepository.class);
        UUID userId = UUID.randomUUID();
        when(repository.tryConsume(eq(userId), eq("DAY"), any(), anyInt(), eq(1))).thenReturn(true);
        when(repository.tryConsume(eq(userId), eq("MINUTE"), any(), anyInt(), eq(1)))
                .thenReturn(false);

        assertThatThrownBy(() -> new TranslateRateLimiter(repository, properties,
                        Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC))
                .consumeOne(userId))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(ApiErrorCode.RATE_LIMITED));
    }

    @Test
    void tryConsumeReportsExhaustionWithoutThrowing() {
        TranslateRequestLimitRepository repository = mock(TranslateRequestLimitRepository.class);
        UUID userId = UUID.randomUUID();
        when(repository.tryConsume(eq(userId), eq("MINUTE"), any(), anyInt(), eq(1))).thenReturn(false);

        boolean allowed = new TranslateRateLimiter(repository, properties,
                Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC))
                .tryConsume(userId, 1);

        assertThat(allowed).isFalse();
        // A minute-window refusal must not spend any of the daily allowance.
        verify(repository, never()).tryConsume(eq(userId), eq("DAY"), any(), anyInt(), anyInt());
    }

    @Test
    void dailyRefusalRefundsTheMinuteWindow() {
        TranslateRequestLimitRepository repository = mock(TranslateRequestLimitRepository.class);
        UUID userId = UUID.randomUUID();
        when(repository.tryConsume(eq(userId), eq("MINUTE"), any(), anyInt(), eq(1))).thenReturn(true);
        when(repository.tryConsume(eq(userId), eq("DAY"), any(), anyInt(), eq(1))).thenReturn(false);

        boolean allowed = new TranslateRateLimiter(repository, properties,
                Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC))
                .tryConsume(userId, 1);

        assertThat(allowed).isFalse();
        // The minute token consumed before the daily refusal is returned in
        // the same transaction: a refusal spends nothing.
        verify(repository).refund(userId, "MINUTE",
                Instant.parse("2026-11-16T12:00:00Z"), 1);
    }

    @Test
    void batchConsumesRequestedAmount() {
        TranslateRequestLimitRepository repository = mock(TranslateRequestLimitRepository.class);
        UUID userId = UUID.randomUUID();
        when(repository.tryConsume(eq(userId), eq("DAY"), any(), anyInt(), eq(5))).thenReturn(true);
        when(repository.tryConsume(eq(userId), eq("MINUTE"), any(), anyInt(), eq(5))).thenReturn(true);

        new TranslateRateLimiter(repository, properties, Clock.systemUTC()).consume(userId, 5);

        verify(repository).tryConsume(eq(userId), eq("DAY"), any(), anyInt(), eq(5));
    }
}
