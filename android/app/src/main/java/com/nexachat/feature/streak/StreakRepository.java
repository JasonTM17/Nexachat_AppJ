package com.nexachat.feature.streak;

import androidx.annotation.NonNull;
import com.nexachat.core.network.ApiCalls;

/** Streak reads and the daily check-in. Callbacks arrive on the main thread. */
public interface StreakRepository {

    interface Callback<T> {
        void onSuccess(@NonNull T value);

        void onFailure();
    }

    /** The caller's check-in streak plus the reminder flag, from {@code GET /streaks/me}. */
    @NonNull
    ApiCalls.Cancellable loadMine(@NonNull Callback<StreakDto> callback);

    /** The streak of one direct conversation; also refreshes the cached inbox badge. */
    @NonNull
    ApiCalls.Cancellable loadConversation(
            @NonNull String conversationId, @NonNull Callback<StreakDto> callback);

    /** True while today's check-in has not been recorded on this device. */
    boolean isCheckInDue();

    /**
     * Sends the check-in and remembers the device-local day on success. A failure stores
     * nothing, so the next foreground retries.
     */
    @NonNull
    ApiCalls.Cancellable checkIn(@NonNull Callback<StreakDto> callback);
}
