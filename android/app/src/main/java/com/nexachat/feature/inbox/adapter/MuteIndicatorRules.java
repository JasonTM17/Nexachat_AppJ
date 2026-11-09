package com.nexachat.feature.inbox.adapter;

import androidx.annotation.Nullable;

/** Pure timing rules for refreshing timed mute indicators while an inbox remains open. */
final class MuteIndicatorRules {
    private MuteIndicatorRules() { }

    static long refreshDelayMillis(boolean muted, @Nullable Long mutedUntilEpochMs, long nowEpochMs) {
        if (!muted || mutedUntilEpochMs == null || mutedUntilEpochMs <= nowEpochMs) return 0L;
        return mutedUntilEpochMs - nowEpochMs;
    }
}
