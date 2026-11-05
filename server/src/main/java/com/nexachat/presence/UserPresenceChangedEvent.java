package com.nexachat.presence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserPresenceChangedEvent(UUID userId, boolean online, Instant lastSeenAt,
                                       List<UUID> recipientIds) {
}
