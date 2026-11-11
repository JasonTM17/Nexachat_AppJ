package com.nexachat.user;

import java.util.List;
import java.util.UUID;

/**
 * Snapshot needed to remove Firebase authorization projections after the user
 * and membership rows have been deleted. Account-deletion code publishes this
 * only after it has captured the user's current authorization edges.
 */
public record UserAccountDeletedEvent(
        UUID userId,
        List<UUID> conversationIds,
        List<UUID> directPeerUserIds,
        List<UUID> blockedPeerUserIds,
        List<UUID> callIds) {

    public UserAccountDeletedEvent {
        conversationIds = List.copyOf(conversationIds);
        directPeerUserIds = List.copyOf(directPeerUserIds);
        blockedPeerUserIds = List.copyOf(blockedPeerUserIds);
        callIds = List.copyOf(callIds);
    }
}
