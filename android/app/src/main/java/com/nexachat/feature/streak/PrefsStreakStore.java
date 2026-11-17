package com.nexachat.feature.streak;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import dagger.hilt.android.qualifiers.ApplicationContext;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;

/** SharedPreferences-backed {@link StreakStore}. */
@Singleton
public final class PrefsStreakStore implements StreakStore {
    private static final String PREFS = "nexachat_streaks";
    private static final String KEY_LAST_CHECK_IN = "last_check_in_day";
    private static final String KEY_CELEBRATED = "celebrated";
    /** Bounds the set; a milestone chain is only re-celebrated if its key was evicted. */
    private static final int MAX_KEYS = 400;

    private final SharedPreferences prefs;

    @Inject
    public PrefsStreakStore(@ApplicationContext @NonNull Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Nullable
    @Override
    public synchronized String lastCheckInDay() {
        return prefs.getString(KEY_LAST_CHECK_IN, null);
    }

    @Override
    public synchronized void saveLastCheckInDay(@NonNull String day) {
        prefs.edit().putString(KEY_LAST_CHECK_IN, day).apply();
    }

    @Override
    public synchronized boolean isCelebrated(@NonNull String key) {
        return prefs.getStringSet(KEY_CELEBRATED, Set.of()).contains(key);
    }

    @Override
    public synchronized void markCelebrated(@NonNull String key) {
        Set<String> next = new HashSet<>(prefs.getStringSet(KEY_CELEBRATED, Set.of()));
        if (next.size() >= MAX_KEYS) {
            next.clear();
        }
        next.add(key);
        prefs.edit().putStringSet(KEY_CELEBRATED, next).apply();
    }

    @Override
    public synchronized void clear() {
        prefs.edit().clear().apply();
    }
}
