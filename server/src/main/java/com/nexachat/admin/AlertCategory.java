package com.nexachat.admin;

import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;

/** Categories the sensitive-message pipeline can raise. */
public enum AlertCategory {
    SELF_HARM,
    THREAT_VIOLENCE,
    SCAM_PHISHING,
    CREDENTIAL_SHARING,
    ID_NUMBER,
    MINOR_SOLICITATION,
    DOXXING,
    CUSTOM;

    public static AlertCategory parse(String raw) {
        try {
            return raw == null ? null : AlertCategory.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.of(ApiErrorCode.VALIDATION_FAILED, "Unknown alert category");
        }
    }
}
