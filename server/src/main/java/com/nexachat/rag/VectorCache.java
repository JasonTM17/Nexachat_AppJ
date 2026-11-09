package com.nexachat.rag;

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

    public int size() {
        return cache.size();
    }

    public void clear() {
        cache.clear();
    }

    private record Key(String model, UUID messageId) {
    }
}
