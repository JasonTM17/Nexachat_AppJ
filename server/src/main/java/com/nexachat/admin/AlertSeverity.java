package com.nexachat.admin;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;

/** Alert severity ordering — {@link #rank} drives worst-of merges and queue sort. */
public enum AlertSeverity {
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    CRITICAL(4);

    private final int rank;

    AlertSeverity(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public static AlertSeverity parse(String raw) {
        try {
            return raw == null ? null : AlertSeverity.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Unknown alert severity");
        }
    }

    public static AlertSeverity max(AlertSeverity left, AlertSeverity right) {
        return left.rank >= right.rank ? left : right;
    }
}
