package com.nexachat.notification;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import dagger.hilt.android.qualifiers.ApplicationContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Keeps a bounded 30-day occurrence ledger without storing raw account/message identifiers. */
@Singleton
public final class NotificationOccurrenceStore implements NotificationOccurrenceLedger {
    private static final String PREFS_NAME = "notification_occurrences";
    private static final String KEY_PREFIX = "occurrence.";
    private static final long RETENTION_MS = 30L * 24L * 60L * 60L * 1_000L;
    private static final int MAX_ENTRIES = 1_000;

    private final SharedPreferences preferences;

    @Inject
    public NotificationOccurrenceStore(@ApplicationContext @NonNull Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Override
    public synchronized boolean deliverOnce(String occurrenceKey, BooleanSupplier publish) {
        if (occurrenceKey == null || occurrenceKey.isBlank()) return false;
        long now = System.currentTimeMillis();
        String storedKey = KEY_PREFIX + digest(occurrenceKey);
        long previous = preferences.getLong(storedKey, 0L);
        if (previous > now - RETENTION_MS) return true;

        if (!publish.getAsBoolean()) return false;
        SharedPreferences.Editor editor = preferences.edit().putLong(storedKey, now);
        prune(editor, now);
        if (!editor.commit()) {
            throw new IllegalStateException("Could not persist notification occurrence");
        }
        return true;
    }

    private void prune(SharedPreferences.Editor editor, long now) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (!entry.getKey().startsWith(KEY_PREFIX) || !(entry.getValue() instanceof Long timestamp)) {
                continue;
            }
            if (timestamp <= now - RETENTION_MS) {
                editor.remove(entry.getKey());
            } else {
                entries.add(Map.entry(entry.getKey(), timestamp));
            }
        }
        entries.sort(Comparator.comparingLong(Map.Entry::getValue));
        int excess = entries.size() - MAX_ENTRIES + 1;
        for (int i = 0; i < excess; i++) {
            editor.remove(entries.get(i).getKey());
        }
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
