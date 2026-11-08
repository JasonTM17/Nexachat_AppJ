package com.nexachat.core.media;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small synchronized access-order cache shared by decoded media helpers. */
public final class BoundedLruCache<K, V> {

    private final int maximumEntries;
    private final LinkedHashMap<K, V> entries = new LinkedHashMap<>(16, 0.75f, true);

    public BoundedLruCache(int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximumEntries must be positive");
        }
        this.maximumEntries = maximumEntries;
    }

    public synchronized V get(K key) {
        return entries.get(key);
    }

    public synchronized void put(K key, V value) {
        entries.put(key, value);
        while (entries.size() > maximumEntries) {
            Map.Entry<K, V> eldest = entries.entrySet().iterator().next();
            entries.remove(eldest.getKey());
        }
    }

    public synchronized int size() {
        return entries.size();
    }

    public int maximumEntries() {
        return maximumEntries;
    }
}
