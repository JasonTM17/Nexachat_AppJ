package com.nexachat.presence;

import java.time.Instant;
import java.util.UUID;

public record DirectPeerPresenceRow(UUID conversationId, UUID userId,
                                    boolean showPresence, Instant lastSeenAt) {
}
