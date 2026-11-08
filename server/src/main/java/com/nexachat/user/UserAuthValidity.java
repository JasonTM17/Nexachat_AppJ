package com.nexachat.user;

import java.time.Instant;
import java.util.UUID;

/**
 * Projection for the JWT filter's hot-path lookup: the account id (non-null
 * means the account exists) plus the access-token cutoff, which is null when no
 * logout or password change has happened yet.
 *
 * <p>A dedicated record rather than {@code Optional<Instant>}: a scalar
 * projection cannot distinguish "no such account" from "cutoff is null", and
 * the filter must treat those two cases differently.
 */
public record UserAuthValidity(UUID id, Instant tokensValidAfter) {
}
