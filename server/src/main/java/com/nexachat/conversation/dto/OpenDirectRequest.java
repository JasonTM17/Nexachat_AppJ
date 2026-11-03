package com.nexachat.conversation.dto;

import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * POST /conversations/direct body. Exactly one of {@code phone} or
 * {@code userId} must be provided — XOR is enforced in the service layer
 * (same convention as GET /users/search).
 */
public record OpenDirectRequest(
        @Size(max = 32) String phone,
        UUID userId
) {
}
