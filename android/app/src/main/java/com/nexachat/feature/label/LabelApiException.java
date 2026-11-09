package com.nexachat.feature.label;

import androidx.annotation.Nullable;

/** HTTP response details retained for localized label error handling. */
public final class LabelApiException extends RuntimeException {
    private final int status;
    @Nullable private final String errorCode;

    public LabelApiException(int status, @Nullable String errorCode) {
        super("Label request failed with HTTP " + status);
        this.status = status;
        this.errorCode = errorCode;
    }

    public int getStatus() { return status; }
    @Nullable public String getErrorCode() { return errorCode; }
}
