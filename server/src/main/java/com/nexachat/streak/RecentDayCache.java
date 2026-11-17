package com.nexachat.streak;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Bounded, best-effort memory of "this (scope, user) pair already recorded
 * streak day D". It only ever skips work that is idempotent in the database,
 * so a miss, an eviction or another instance's write is harmless. Entries are
 * written strictly after the owning transaction commits.
 */
final class RecentDayCache {

    /** {@code scopeId} is the conversation id, or {@code null} for the user-level streak. */
    record Key(UUID scopeId, UUID userId) {
    }

    private final Map<Key, LocalDate> entries;

    RecentDayCache(int maxEntries) {
        this.entries = Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, LocalDate> eldest) {
                return size() > maxEntries;
            }
        });
    }

    boolean recorded(Key key, LocalDate day) {
        return day.equals(entries.get(key));
    }

    void remember(Key key, LocalDate day) {
        entries.merge(key, day, (old, next) -> next.isAfter(old) ? next : old);
    }

    void clear() {
        entries.clear();
    }

    int size() {
        return entries.size();
    }
}
