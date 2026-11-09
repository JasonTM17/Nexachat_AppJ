package com.nexachat.core.network;

/**
 * Pure rules for refresh-token rotation outcomes.
 *
 * <p>Only an auth-specific 401/403 response proves the refresh token is unusable.
 * Generic validation errors and transient statuses leave the session intact.
 */
public final class TokenRefreshRules {

    private TokenRefreshRules() {}

    /** Only an auth-specific response can prove that the stored refresh token is unusable. */
    public static boolean isDefinitiveRejection(int status, String errorCode) {
        if (status == 401) {
            return "TOKEN_INVALID".equals(errorCode)
                    || "TOKEN_REUSED".equals(errorCode)
                    || "TOKEN_EXPIRED".equals(errorCode)
                    || "UNAUTHORIZED".equals(errorCode);
        }
        return status == 403 && "FORBIDDEN".equals(errorCode);
    }
}
