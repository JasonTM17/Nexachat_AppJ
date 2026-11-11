package com.nexachat.user;

import java.util.UUID;

/** Signals that a durable account can be mirrored into optional realtime authorization state. */
public record UserAccountCreatedEvent(UUID userId) {
}
