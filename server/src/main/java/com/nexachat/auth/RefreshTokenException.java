package com.nexachat.auth;

import com.nexachat.common.error.ApiErrorCode;

import java.util.UUID;

/**
 * Internal signal for a failed refresh — NOT an API exception.
 *
 * <p>Why it exists: when a revoked/expired token is presented, the spec requires
 * revoking the user's ENTIRE token family (reuse detection, §13). The rotation
 * method runs inside a transaction that rolls back when this exception is thrown,
 * so the family revocation cannot happen there — it must be committed by the
 * caller in a separate transaction. This exception therefore carries
 * {@link #userId} (nullable: unknown tokens have no family) and the
 * {@link #errorCode} to report ({@code TOKEN_REUSED} vs {@code TOKEN_INVALID}).
 */
public class RefreshTokenException extends RuntimeException {

    private final ApiErrorCode errorCode;
    private final UUID userId; // null when the token hash is unknown → no family to revoke

    private RefreshTokenException(ApiErrorCode errorCode, String message, UUID userId) {
        super(message);
        this.errorCode = errorCode;
        this.userId = userId;
    }

    public static RefreshTokenException invalid(UUID userId) {
        return new RefreshTokenException(ApiErrorCode.TOKEN_INVALID, "Refresh token is not valid", userId);
    }

    public static RefreshTokenException reused(UUID userId) {
        return new RefreshTokenException(ApiErrorCode.TOKEN_REUSED, "Refresh token was already used or revoked", userId);
    }

    public ApiErrorCode errorCode() {
        return errorCode;
    }

    public UUID userId() {
        return userId;
    }
}
