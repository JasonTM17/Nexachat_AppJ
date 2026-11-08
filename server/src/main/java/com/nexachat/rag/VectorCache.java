package com.nexachat.rag;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Size-bounded access-order LRU for message vectors. */
public final class VectorCache {

    private final Map<UUID, float[]> cache;

    public VectorCache(int maxSize) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be positive");
        }
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, float[]> eldest) {
                return size() > maxSize;
            }
        });
    }

    public float[] get(UUID messageId) {
        return cache.get(messageId);
    }

    public void put(UUID messageId, float[] vector) {
        if (messageId != null && vector != null) {
            cache.put(messageId, vector);
        }
    }

    public void putAll(Map<UUID, float[]> vectors) {
        vectors.forEach(this::put);
    }

    public int size() {
        return cache.size();
    }

    public void clear() {
        cache.clear();
    }
}
