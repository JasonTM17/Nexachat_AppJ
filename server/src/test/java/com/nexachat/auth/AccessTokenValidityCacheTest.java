package com.nexachat.auth;

import com.nexachat.auth.AccessTokenValidityCache.Validity;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The validity cache bounds what the JWT filter remembers: entries expire
 * within the access-token lifetime, the map cannot grow without limit, and an
 * explicit eviction makes revocation immediate.
 */
class AccessTokenValidityCacheTest {

    private static final JwtProperties PROPERTIES =
            new JwtProperties("test-secret-at-least-32-bytes-long", 900, 14);

    @Test
    void returnsEmptyForUnknownUsers() {
        AccessTokenValidityCache cache = new AccessTokenValidityCache(PROPERTIES, 60, 10);

        assertThat(cache.get(UUID.randomUUID())).isEmpty();
    }

    @Test
    void servesStoredValidityAndDistinguishesMissingAccounts() {
        AccessTokenValidityCache cache = new AccessTokenValidityCache(PROPERTIES, 60, 10);
        UUID userId = UUID.randomUUID();
        Instant cutoff = Instant.now().minusSeconds(5);

        cache.put(userId, new Validity(true, cutoff));

        Optional<Validity> loaded = cache.get(userId);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().present()).isTrue();
        assertThat(loaded.get().tokensValidAfter()).isEqualTo(cutoff);

        UUID gone = UUID.randomUUID();
        cache.put(gone, Validity.missing());
        assertThat(cache.get(gone)).isPresent();
        assertThat(cache.get(gone).get().present()).isFalse();
    }

    @Test
    void entryExpiresAfterTheTtl() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-13T00:00:00Z"));
        AccessTokenValidityCache cache = new AccessTokenValidityCache(PROPERTIES, 1, 10, clock);
        UUID userId = UUID.randomUUID();
        cache.put(userId, new Validity(true, null));

        assertThat(cache.get(userId)).isPresent();

        clock.advance(Duration.ofSeconds(1).minusNanos(1));
        assertThat(cache.get(userId)).isPresent();

        clock.advance(Duration.ofNanos(1));

        assertThat(cache.get(userId)).isEmpty();
        assertThat(cache.size()).isZero();
    }

    @Test
    void evictDropsOneEntryWithoutTouchingOthers() {
        AccessTokenValidityCache cache = new AccessTokenValidityCache(PROPERTIES, 60, 10);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        cache.put(first, new Validity(true, null));
        cache.put(second, new Validity(true, null));

        cache.evict(first);

        assertThat(cache.get(first)).isEmpty();
        assertThat(cache.get(second)).isPresent();
    }

    @Test
    void generationRejectsAStaleReadAfterEviction() {
        AccessTokenValidityCache cache = new AccessTokenValidityCache(PROPERTIES, 60, 10);
        UUID userId = UUID.randomUUID();
        long beforeDatabaseRead = cache.generation();

        cache.evict(userId);

        assertThat(cache.putIfGeneration(userId, new Validity(true, null), beforeDatabaseRead)).isFalse();
        assertThat(cache.get(userId)).isEmpty();
    }

    @Test
    void mapIsBoundedByMaxEntries() {
        AccessTokenValidityCache cache = new AccessTokenValidityCache(PROPERTIES, 60, 4);
        for (int i = 0; i < 8; i++) {
            cache.put(UUID.randomUUID(), new Validity(true, null));
        }

        assertThat(cache.size()).isLessThanOrEqualTo(4);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant) {
            this(instant, ZoneId.of("UTC"));
        }

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
