package com.nexachat.feature.safety;

import androidx.annotation.Nullable;

/** HTTP failure from a safety endpoint; carries the server error code when present. */
public final class SafetyApiException extends RuntimeException {
    private final int status;
    @Nullable private final String code;

    public SafetyApiException(int status, @Nullable String code) {
        super(code == null ? "HTTP " + status : code);
        this.status = status;
        this.code = code;
    }

    public int getStatus() {
        return status;
    }

    @Nullable public String getCode() {
        return code;
    }

    public boolean isRateLimited() {
        return status == 429;
    }
}
