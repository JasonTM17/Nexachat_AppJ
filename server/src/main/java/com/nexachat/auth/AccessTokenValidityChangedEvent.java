package com.nexachat.auth;

import java.util.UUID;

/** Published when validity inputs such as suspension change without revoking tokens. */
public record AccessTokenValidityChangedEvent(UUID userId) {
}
