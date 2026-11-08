package com.nexachat.core.media;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Caches placeholder factories' drawable state by hash without retaining views. */
public final class BlurHashPlaceholderCache<T> {

    public static final int MAX_ENTRIES = 64;

    private final BoundedLruCache<String, T> entries = new BoundedLruCache<>(MAX_ENTRIES);

    @Nullable
    public synchronized T getOrCreate(
            @Nullable String hash,
            @NonNull Factory<T> factory) {
        if (hash == null || !BlurHashDecoder.isValid(hash)) {
            return null;
        }
        T cached = entries.get(hash);
        if (cached != null) {
            return cached;
        }
        try {
            T created = factory.create(hash);
            if (created != null) {
                entries.put(hash, created);
            }
            return created;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public int size() {
        return entries.size();
    }

    @NonNull
    public static <T> Factory<T> factory(@NonNull Creator<T> creator) {
        return creator::create;
    }

    @FunctionalInterface
    public interface Factory<T> {
        @Nullable T create(@NonNull String hash);
    }

    @FunctionalInterface
    public interface Creator<T> {
        @Nullable T create(@NonNull String hash);
    }
}
