package com.nexachat.common.dto;

import java.util.UUID;
import java.time.Instant;

/**
 * Authenticated-user principal stored in the {@code SecurityContext} by the JWT filter.
 *
 * <p>Design choice: the principal carries a typed
 * {@link UUID} user id rather than stuffing it into {@code Authentication.getName()}.
 * Any module resolves the caller via
 * {@code @AuthenticationPrincipal JwtPrincipal principal} with zero parsing.
 * Placed in {@code common} so feature modules (contact/conversation/message)
 * can use it without depending on the auth package.
 */
public record JwtPrincipal(UUID userId, Instant issuedAt, Instant expiresAt) {

    /** Compatibility constructor for internal callers that only need the user id. */
    public JwtPrincipal(UUID userId) {
        this(userId, null, null);
    }
}
