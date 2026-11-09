package com.nexachat.poll;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Expiry is exclusive at the boundary: a poll with {@code closes_at == now}
 * is already closed, so a vote can never land at the configured instant.
 */
class PollTest {

    @Test
    void expiryBoundaryClosesAtTheConfiguredInstant() {
        Instant closesAt = Instant.parse("2026-11-09T10:00:00Z");

        assertThat(Poll.isExpired(closesAt, closesAt.minusNanos(1))).isFalse();
        assertThat(Poll.isExpired(closesAt, closesAt)).isTrue();
        assertThat(Poll.isExpired(closesAt, closesAt.plusNanos(1))).isTrue();
    }

    @Test
    void noExpiryNeverCloses() {
        assertThat(Poll.isExpired(null, Instant.now())).isFalse();
    }
}
