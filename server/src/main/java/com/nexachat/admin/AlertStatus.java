package com.nexachat.admin;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;

/** Alert triage workflow states. */
public enum AlertStatus {
    OPEN,
    ACKNOWLEDGED,
    RESOLVED,
    DISMISSED;

    public static AlertStatus parse(String raw) {
        try {
            return raw == null ? null : AlertStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Unknown alert status");
        }
    }
}
