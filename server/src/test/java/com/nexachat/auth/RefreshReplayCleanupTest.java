package com.nexachat.auth;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefreshReplayCleanupTest {

    @Test
    void usesOneUtcInstantAndCapsEachCleanupBatch() {
        RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
        when(repository.scrubExpiredReplayPayloads(Instant.parse("2026-11-10T08:00:00.123456Z"), 5_000))
                .thenReturn(4);
        RefreshReplayProperties properties = new RefreshReplayProperties();
        properties.setCleanupBatchSize(50_000);
        RefreshReplayCleanup cleanup = new RefreshReplayCleanup(repository, properties,
                Clock.fixed(Instant.parse("2026-11-10T08:00:00.123456789Z"), ZoneOffset.UTC));

        int rows = cleanup.scrubExpiredPayloads();

        assertThat(rows).isEqualTo(4);
        verify(repository).scrubExpiredReplayPayloads(Instant.parse("2026-11-10T08:00:00.123456Z"), 5_000);
    }

    @Test
    void cleanupBatchCannotBecomeZeroOrNegative() {
        RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
        when(repository.scrubExpiredReplayPayloads(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(1))).thenReturn(0);
        RefreshReplayProperties properties = new RefreshReplayProperties();
        properties.setCleanupBatchSize(0);
        RefreshReplayCleanup cleanup = new RefreshReplayCleanup(repository, properties,
                Clock.fixed(Instant.parse("2026-11-10T08:00:00Z"), ZoneOffset.UTC));

        assertThat(cleanup.scrubExpiredPayloads()).isZero();
        verify(repository).scrubExpiredReplayPayloads(Instant.parse("2026-11-10T08:00:00Z"), 1);
    }
}
