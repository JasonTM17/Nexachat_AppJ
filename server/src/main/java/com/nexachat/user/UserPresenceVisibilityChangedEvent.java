package com.nexachat.user;

import java.util.UUID;

/** Signals that the durable show-presence preference changed. */
public record UserPresenceVisibilityChangedEvent(UUID userId, boolean showPresence) {
}
