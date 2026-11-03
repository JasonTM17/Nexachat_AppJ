package com.nexachat.contact.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One contact row as the client sees it. {@code aliasName} is the owner's
 * override; when {@code null} the client renders {@code displayName}.
 * {@code createdAt} is included so the client can sort "recently added".
 */
public record ContactResponse(
        UUID contactUserId,
        String phone,
        String displayName,
        String aliasName,
        Instant createdAt
) {
}
