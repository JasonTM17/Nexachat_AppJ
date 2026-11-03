package com.nexachat.ws;

import java.security.Principal;
import java.util.UUID;

/**
 * Authenticated STOMP session identity: carries the typed user id while
 * {@link #getName()} returns its string form — that string is what
 * {@code DefaultUserDestinationResolver} and {@code SimpMessagingTemplate
 * .convertAndSendToUser} match for {@code /user/queue/*} routing, so it MUST be
 * the plain user id, not a {@code toString()} of a richer record.
 */
public record StompUserPrincipal(UUID userId) implements Principal {

    @Override
    public String getName() {
        return userId.toString();
    }
}
