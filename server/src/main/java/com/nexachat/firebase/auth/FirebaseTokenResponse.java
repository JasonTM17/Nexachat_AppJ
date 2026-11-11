package com.nexachat.firebase.auth;

import java.time.Instant;
import java.util.UUID;

public record FirebaseTokenResponse(
        String customToken,
        String databaseUrl,
        UUID bridgeId,
        Instant leaseUntil,
        String transport) {
}
