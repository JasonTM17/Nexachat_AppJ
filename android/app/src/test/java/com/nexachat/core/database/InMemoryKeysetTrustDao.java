package com.nexachat.core.database;

import androidx.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Map-backed {@link KeysetTrustDao} for pure-JVM tests. All methods are
 * synchronized so the inherited compare-and-flip keeps its atomicity contract;
 * sharing one instance across stores simulates a durable table across restarts.
 */
public final class InMemoryKeysetTrustDao extends KeysetTrustDao {

    private record Key(String ownerUserId, String conversationId) {
    }

    private final Map<Key, KeysetTrustEntity> rows = new HashMap<>();

    @Override
    @Nullable
    public synchronized KeysetTrustEntity find(String ownerUserId, String conversationId) {
        return rows.get(new Key(ownerUserId, conversationId));
    }

    @Override
    public synchronized void put(KeysetTrustEntity row) {
        rows.put(new Key(row.ownerUserId, row.conversationId), row);
    }

    @Override
    @Nullable
    public synchronized KeysetTrustEntity checkAndMarkChanged(String ownerUserId,
            String conversationId, String observedDigestHex, long nowMs) {
        return super.checkAndMarkChanged(ownerUserId, conversationId,
                observedDigestHex, nowMs);
    }

    @Override
    @Nullable
    public synchronized KeysetTrustEntity checkAndMarkReshaped(String ownerUserId,
            String conversationId, long nowMs) {
        return super.checkAndMarkReshaped(ownerUserId, conversationId, nowMs);
    }
}
