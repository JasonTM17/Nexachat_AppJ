package com.nexachat.core.network;

/**
 * Pure rules for refresh-token rotation outcomes.
 *
 * <p>400/401/403 mean the refresh token cannot be retried (validation failure,
 * TOKEN_INVALID/EXPIRED/REUSED, or forbidden). 408 and 429 are not session death.
 */
public final class TokenRefreshRules {

    private TokenRefreshRules() {}

    /** Contract rejections that mean this refresh token cannot be retried. */
    public static boolean isDefinitiveRejection(int status) {
        return status == 400 || status == 401 || status == 403;
    }
}
