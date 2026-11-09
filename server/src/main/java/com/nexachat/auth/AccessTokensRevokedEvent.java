package com.nexachat.auth;

import java.util.UUID;

/** Published in the transaction that advances a user's access-token cutoff. */
public record AccessTokensRevokedEvent(UUID userId) {
}
