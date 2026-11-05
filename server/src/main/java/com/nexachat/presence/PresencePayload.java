package com.nexachat.presence;

import java.time.Instant;
import java.util.UUID;

public record PresencePayload(UUID userId, boolean online, Instant lastSeenAt) {
}
