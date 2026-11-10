package com.nexachat.recovery;

import java.time.Instant;
import java.util.UUID;

record EmailOtpRecord(
        UUID id,
        UUID userId,
        OtpPurpose purpose,
        String email,
        String codeHash,
        Instant expiresAt,
        int attempts,
        Instant consumedAt) {
}
