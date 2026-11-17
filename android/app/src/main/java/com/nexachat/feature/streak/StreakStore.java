package com.nexachat.feature.streak;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Device-local streak bookkeeping: the last check-in day and which milestones were celebrated. */
public interface StreakStore {

    /** Device-local day of the last successful check-in, ISO {@code yyyy-MM-dd}. */
    @Nullable
    String lastCheckInDay();

    void saveLastCheckInDay(@NonNull String day);

    boolean isCelebrated(@NonNull String key);

    void markCelebrated(@NonNull String key);

    /** Forgets everything; called when the session ends. */
    void clear();
}
