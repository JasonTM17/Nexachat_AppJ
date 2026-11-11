package com.nexachat.auth;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RefreshReplayCleanupTest {

    @Test
    void capsEachCleanupBatchAndLetsDatabaseChooseExpirationTime() {
        RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
        when(repository.scrubExpiredReplayPayloads(5_000)).thenReturn(4);
        RefreshReplayProperties properties = new RefreshReplayProperties();
        properties.setCleanupBatchSize(50_000);
        RefreshReplayCleanup cleanup = new RefreshReplayCleanup(repository, properties);

        int rows = cleanup.scrubExpiredPayloads();

        assertThat(rows).isEqualTo(4);
        verify(repository).scrubExpiredReplayPayloads(5_000);
    }

    @Test
    void cleanupBatchCannotBecomeZeroOrNegative() {
        RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
        when(repository.scrubExpiredReplayPayloads(org.mockito.ArgumentMatchers.eq(1))).thenReturn(0);
        RefreshReplayProperties properties = new RefreshReplayProperties();
        properties.setCleanupBatchSize(0);
        RefreshReplayCleanup cleanup = new RefreshReplayCleanup(repository, properties);

        assertThat(cleanup.scrubExpiredPayloads()).isZero();
        verify(repository).scrubExpiredReplayPayloads(1);
    }
}
