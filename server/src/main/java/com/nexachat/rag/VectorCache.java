package com.nexachat.rag;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Size-bounded access-order LRU for message vectors. Entries are scoped by the
 * embedding model identity as well as the message id, so vectors computed in
 * one embedding space are never reused for another.
 */
public final class VectorCache {

    private final Map<Key, float[]> cache;

    public VectorCache(int maxSize) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be positive");
        }
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, float[]> eldest) {
                return size() > maxSize;
            }
        });
    }

    public float[] get(String model, UUID messageId) {
        if (model == null || messageId == null) {
            return null;
        }
        return cache.get(new Key(model, messageId));
    }

    public void put(String model, UUID messageId, float[] vector) {
        if (model != null && messageId != null && vector != null) {
            cache.put(new Key(model, messageId), vector);
        }
    }

    public void putAll(String model, Map<UUID, float[]> vectors) {
        if (vectors == null) {
            return;
        }
        vectors.forEach((messageId, vector) -> put(model, messageId, vector));
    }

    /**
     * Evicts every cached vector of the given messages, across all models. The
     * cache is keyed by {@code (model, messageId)} and carries no conversation,
     * so message-scoped eviction is the granularity the cache supports; callers
     * pass the message ids of one conversation (e.g. a member's messages after
     * an AI opt-out). Iteration is synchronized on the backing map, matching
     * the concurrency of the other accessors.
     */
    public void removeMessageIds(Collection<UUID> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return;
        }
        synchronized (cache) {
            cache.entrySet().removeIf(entry -> messageIds.contains(entry.getKey().messageId()));
        }
    }

    public int size() {
        return cache.size();
    }

    public void clear() {
        cache.clear();
    }

    private record Key(String model, UUID messageId) {
    }
}
