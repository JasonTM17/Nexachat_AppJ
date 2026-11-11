package com.nexachat.core.realtime.ephemeral;

import androidx.annotation.Nullable;
import com.nexachat.data.PeerPresence;
import java.util.Collection;

/** Pure Firebase presence/typing rules shared by the SDK adapter and JVM tests. */
public final class EphemeralTimeRules {
    public static final long TYPING_THROTTLE_MS = 3_000L;
    public static final long TYPING_TTL_MS = 5_000L;
    public static final long MAX_BRIDGE_LEASE_MS = 120_000L;

    private EphemeralTimeRules() { }

    @Nullable
    public static PeerPresence aggregatePresence(
            Collection<PresenceRecord> records, long serverNowEpochMs) {
        boolean online = false;
        Long newestLastSeen = null;
        for (PresenceRecord record : records) {
            if (record == null) continue;
            if (record.online && record.leaseUntilEpochMs > serverNowEpochMs) {
                online = true;
            } else if (!record.online && record.lastSeenEpochMs != null
                    && (newestLastSeen == null || record.lastSeenEpochMs > newestLastSeen)) {
                newestLastSeen = record.lastSeenEpochMs;
            }
        }
        if (online) return new PeerPresence(true, null);
        return new PeerPresence(false, newestLastSeen);
    }

    public static boolean isTypingActive(long expiresAtEpochMs, long serverNowEpochMs) {
        return expiresAtEpochMs > serverNowEpochMs;
    }

    public static boolean canSendTyping(long lastSentElapsedMs, long nowElapsedMs) {
        return lastSentElapsedMs < 0L
                || nowElapsedMs - lastSentElapsedMs >= TYPING_THROTTLE_MS;
    }

    public static long typingTtl(long nowEpochMs, long expiresAtEpochMs) {
        return Math.max(0L, Math.min(TYPING_TTL_MS, expiresAtEpochMs - nowEpochMs));
    }

    public record PresenceRecord(
            boolean online,
            @Nullable Long lastSeenEpochMs,
            long leaseUntilEpochMs) { }
}
