package com.nexachat.core.network.dto;

import androidx.annotation.NonNull;
import java.util.Objects;

/** {@code POST /api/v1/users/me/email/verify} body. */
public final class VerifyRecoveryEmailRequest {
    @NonNull private final String code;

    public VerifyRecoveryEmailRequest(@NonNull String code) {
        this.code = Objects.requireNonNull(code, "code");
    }

    @NonNull
    public String getCode() {
        return code;
    }
}
