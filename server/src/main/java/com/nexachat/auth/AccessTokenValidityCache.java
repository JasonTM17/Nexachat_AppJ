package com.nexachat.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Bounded in-memory cache of the JWT filter's per-user validity lookup.
 *
 * <p>The filter needs two facts on every authenticated request: does the
 * account still exist, and is there an access-token cutoff the token's
 * {@code iat} must be after. Loading that from the database on every request
 * would put a query on the hot path, so the answer is cached per user id.
 *
 * <p>Bounds: the TTL never exceeds the access-token lifetime, so a stale entry
 * cannot outlive the token it describes; the map is a bounded LRU, so a burst
 * of distinct user ids cannot grow it without limit. Logout, logout-all and
 * password change also evict the entry explicitly, which makes revocation
 * immediate on a single-node deployment instead of waiting for the TTL.
 *
 * <p>Known limit: the cache is per instance. In a multi-node deployment every
 * node keeps its own entries, so revocation propagates within one TTL rather
 * than instantly. The documented deployment is single-node.
 */
@Component
public class AccessTokenValidityCache {

    /** Cached answer for one user. {@code present=false} means the account is gone. */
    public record Validity(boolean present, Instant tokensValidAfter) {

        public static Validity missing() {
            return new Validity(false, null);
        }
    }

    static final int DEFAULT_MAX_ENTRIES = 5_000;
    static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

    private final Map<UUID, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final int maxEntries;
    private final Duration ttl;
    private final Clock clock;
    private final Object lock = new Object();
    private long generation;

    @org.springframework.beans.factory.annotation.Autowired
    public AccessTokenValidityCache(
            JwtProperties jwtProperties,
            @Value("${nexachat.security.validity-cache-ttl-seconds:60}") long ttlSeconds,
            Clock clock) {
        this(jwtProperties, ttlSeconds, DEFAULT_MAX_ENTRIES, clock);
    }

    AccessTokenValidityCache(JwtProperties jwtProperties, long ttlSeconds, int maxEntries) {
        this(jwtProperties, ttlSeconds, maxEntries, Clock.systemUTC());
    }

    AccessTokenValidityCache(
            JwtProperties jwtProperties,
            long ttlSeconds,
            int maxEntries,
            Clock clock) {
        // Never cache longer than the token can live: a stale "valid" answer
        // must not outlive the access token it was computed for.
        long capSeconds = jwtProperties.accessTtlSeconds();
        long effectiveSeconds = Math.max(1, Math.min(ttlSeconds, capSeconds));
        if (maxEntries < 1) {
            throw new IllegalArgumentException("Validity cache bounds must be positive");
        }
        this.maxEntries = maxEntries;
        this.ttl = Duration.ofSeconds(effectiveSeconds);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** @return the cached validity, or empty when absent or expired */
    public Optional<Validity> get(UUID userId) {
        Instant now = clock.instant();
        synchronized (lock) {
            Entry entry = entries.get(userId);
            if (entry == null) {
                return Optional.empty();
            }
            if (!now.isBefore(entry.cachedAt.plus(ttl))) {
                entries.remove(userId);
                return Optional.empty();
            }
            return Optional.of(entry.validity);
        }
    }

    public void put(UUID userId, Validity validity) {
        Instant now = clock.instant();
        synchronized (lock) {
            putLocked(userId, validity, now);
        }
    }

    /** Snapshot used to prevent a stale in-flight database read from refilling after eviction. */
    public long generation() {
        synchronized (lock) {
            return generation;
        }
    }

    /** Stores only if no revocation eviction occurred after the caller's snapshot. */
    public boolean putIfGeneration(UUID userId, Validity validity, long expectedGeneration) {
        synchronized (lock) {
            if (generation != expectedGeneration) {
                return false;
            }
            putLocked(userId, validity, clock.instant());
            return true;
        }
    }

    /** Drops one user's entry so the next request reloads the authoritative value. */
    public void evict(UUID userId) {
        synchronized (lock) {
            generation++;
            entries.remove(userId);
        }
    }

    @org.springframework.transaction.event.TransactionalEventListener(
            phase = org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT)
    public void onAccessTokensRevoked(AccessTokensRevokedEvent event) {
        evict(event.userId());
    }

    /** Visible for tests: how many users currently hold a cached entry. */
    int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    private void evictExpired(Instant now) {
        Iterator<Map.Entry<UUID, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (!now.isBefore(iterator.next().getValue().cachedAt.plus(ttl))) {
                iterator.remove();
            }
        }
    }

    private void putLocked(UUID userId, Validity validity, Instant now) {
        if (entries.size() >= maxEntries && !entries.containsKey(userId)) {
            evictExpired(now);
            if (entries.size() >= maxEntries) {
                Iterator<UUID> oldest = entries.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
        }
        entries.put(userId, new Entry(validity, now));
    }

    private static final class Entry {
        private final Validity validity;
        private final Instant cachedAt;

        private Entry(Validity validity, Instant cachedAt) {
            this.validity = validity;
            this.cachedAt = cachedAt;
        }
    }
}
