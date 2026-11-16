package com.nexachat.user;

import java.time.Instant;
import java.util.UUID;

/**
 * Projection for the JWT filter's hot-path lookup: the account id (non-null
 * means the account exists), the access-token cutoff (null when no logout or
 * password change has happened yet), the account role carried into the
 * security context for {@code hasRole} checks, and the suspension flag.
 *
 * <p>A dedicated record rather than {@code Optional<Instant>}: a scalar
 * projection cannot distinguish "no such account" from "cutoff is null", and
 * the filter must treat those two cases differently.
 */
public record UserAuthValidity(UUID id, Instant tokensValidAfter, User.Role role,
                               boolean suspended) {

    /** Pre-role call shape: an ordinary, non-suspended account. */
    public UserAuthValidity(UUID id, Instant tokensValidAfter) {
        this(id, tokensValidAfter, User.Role.USER, false);
    }
}
