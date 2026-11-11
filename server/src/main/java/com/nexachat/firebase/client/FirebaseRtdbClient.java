package com.nexachat.firebase.client;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Narrow server-only interface for bridge-session and membership-lease writes. */
public interface FirebaseRtdbClient {

    /** Atomic, server-only multi-location updates for authorization projections. */
    default void applyAuthorizationProjection(Map<String, Object> updates) {
        throw new UnsupportedOperationException("Firebase authorization projections are not supported by this client");
    }

    /** Atomic, server-only writes or deletions for bounded call-status display projections. */
    default void applyCallStatusProjection(Map<String, Object> updates) {
        throw new UnsupportedOperationException("Firebase call-status projections are not supported by this client");
    }

    /** Deletes at most {@code limit} children whose selected numeric lease/expiry is at or before the cutoff. */
    default int deleteExpiredChildren(String parentPath, String orderBy, long cutoffMillis, int limit) {
        throw new UnsupportedOperationException("Firebase lease sweeping is not supported by this client");
    }

    /** Deletes lease-expired presence records with an ETag guard. */
    default int expirePresenceEntries(UUID userId, long cutoffMillis, int limit) {
        throw new UnsupportedOperationException("Firebase presence expiry is not supported by this client");
    }

    /** Returns whether all legacy offline last-seen values have been removed before peer reads are admitted. */
    default boolean presencePrivacyMigrationComplete() {
        return false;
    }

    /** Returns whether an operator has persistently disabled client access to the Firebase bridge. */
    boolean isAdministrativelyDisabled();

    /** Removes at most {@code limit} legacy presence records containing a last-seen timestamp. */
    default int removeLegacyOfflinePresenceEntries(UUID userId, int limit) {
        throw new UnsupportedOperationException("Firebase presence privacy migration is not supported by this client");
    }

    Long readSessionLease(UUID userId, UUID bridgeId);

    void writeSessionLease(UUID userId, UUID bridgeId, long leaseUntilMillis,
                           Map<UUID, Long> conversationLeaseUntilMillis);

    void deleteBridge(UUID userId, UUID bridgeId);

    void revokeUser(UUID userId, List<UUID> conversationIds);
}
