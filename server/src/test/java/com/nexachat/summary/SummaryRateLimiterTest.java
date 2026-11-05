package com.nexachat.summary;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SummaryRateLimiterTest {

    @Test
    void usesUtcMinuteAndDayWindows() {
        SummaryRequestLimitRepository repository = mock(SummaryRequestLimitRepository.class);
        Instant now = Instant.parse("2026-11-05T23:59:45Z");
        UUID userId = UUID.randomUUID();
        when(repository.tryConsume(userId, "DAY", Instant.parse("2026-11-05T00:00:00Z"), 60))
                .thenReturn(true);
        when(repository.tryConsume(userId, "MINUTE", Instant.parse("2026-11-05T23:59:00Z"), 10))
                .thenReturn(true);

        new SummaryRateLimiter(repository, Clock.fixed(now, ZoneOffset.UTC)).consume(userId);

        verify(repository).tryConsume(userId, "DAY", Instant.parse("2026-11-05T00:00:00Z"), 60);
        verify(repository).tryConsume(userId, "MINUTE", Instant.parse("2026-11-05T23:59:00Z"), 10);
    }

    @Test
    void rejectsWhenMinuteLimitIsReached() {
        SummaryRequestLimitRepository repository = mock(SummaryRequestLimitRepository.class);
        Instant now = Instant.parse("2026-11-05T12:34:56Z");
        UUID userId = UUID.randomUUID();
        when(repository.tryConsume(userId, "DAY", Instant.parse("2026-11-05T00:00:00Z"), 60))
                .thenReturn(true);
        when(repository.tryConsume(userId, "MINUTE", Instant.parse("2026-11-05T12:34:00Z"), 10))
                .thenReturn(false);

        assertThatThrownBy(() -> new SummaryRateLimiter(repository, Clock.fixed(now, ZoneOffset.UTC))
                .consume(userId))
                .isInstanceOfSatisfying(ApiException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(ApiErrorCode.RATE_LIMITED));
    }
}
