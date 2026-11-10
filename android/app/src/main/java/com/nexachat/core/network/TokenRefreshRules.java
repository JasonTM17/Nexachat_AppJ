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

    /** Known matching retry after the server recovery window; never logs the user out. */
    public static boolean isRecoveryUnavailable(int status, String errorCode) {
        return status == 409 && "REFRESH_RECOVERY_UNAVAILABLE".equals(errorCode);
    }

    /** Keyed refresh is unavailable before rotation; client may retry with the same key. */
    public static boolean isRefreshServiceUnavailable(int status, String errorCode) {
        return status == 503 && "REFRESH_SERVICE_UNAVAILABLE".equals(errorCode);
    }

    /** Retryable transport or server overload responses that preserve local session state. */
    public static boolean isTransientStatus(int status, String errorCode) {
        if (isRecoveryUnavailable(status, errorCode)
                || isRefreshServiceUnavailable(status, errorCode)) {
            return true;
        }
        if (status == 429) {
            return true;
        }
        return status >= 500;
    }
}
