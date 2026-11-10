package com.nexachat.firebase.auth;

import java.time.Instant;

public record FirebaseLeaseResponse(Instant leaseUntil, String transport) {
}
