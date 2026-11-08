package com.nexachat.feature.chat.voice;

import android.util.LruCache;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Small process cache; the waveform is only 48 bytes per attachment. */
public final class VoiceWaveformCache implements VoicePlaybackController.WaveformStore {

    public static final int MAX_ENTRIES = 100;
    private static final LruCache<String, byte[]> CACHE = new LruCache<>(MAX_ENTRIES);
    private static final VoiceWaveformCache INSTANCE = new VoiceWaveformCache();

    private VoiceWaveformCache() {
    }

    public static VoiceWaveformCache instance() {
        return INSTANCE;
    }

    @Nullable
    @Override
    public synchronized byte[] get(@Nullable String attachmentId) {
        if (attachmentId == null) {
            return null;
        }
        byte[] value = CACHE.get(attachmentId);
        return value == null ? null : value.clone();
    }

    @Override
    public synchronized void put(@NonNull String attachmentId, @NonNull byte[] bars) {
        CACHE.put(attachmentId, bars.clone());
    }

    static synchronized void clearForTesting() {
        CACHE.evictAll();
    }
}
