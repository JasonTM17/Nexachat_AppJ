package com.nexachat.firebase.client;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Narrow server-only interface for bridge-session and membership-lease writes. */
public interface FirebaseRtdbClient {

    Long readSessionLease(UUID userId, UUID bridgeId);

    void writeSessionLease(UUID userId, UUID bridgeId, long leaseUntilMillis,
                           Map<UUID, Long> conversationLeaseUntilMillis);

    void deleteBridge(UUID userId, UUID bridgeId);

    void revokeUser(UUID userId, List<UUID> conversationIds);
}
