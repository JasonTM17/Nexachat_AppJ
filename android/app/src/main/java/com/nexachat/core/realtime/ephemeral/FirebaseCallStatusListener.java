package com.nexachat.core.realtime.ephemeral;

import androidx.annotation.Nullable;
import java.util.Map;

/** Read-only updates from the authenticated server-projected call-status path. */
public interface FirebaseCallStatusListener {
    void onStatusChanged(
            @Nullable String snapshotCallId,
            String authenticatedUserId,
            @Nullable Map<?, ?> payload,
            long serverNowMillis);

    void onUnavailable();
}
